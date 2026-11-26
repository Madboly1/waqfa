package com.madboly.waqfa

import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom

object Drive {
    private const val CID = "509805634059-c70i9tl81c49qeum9jaap6j7obin3o4a.apps.googleusercontent.com"
    const val SCHEME = "com.googleusercontent.apps.509805634059-c70i9tl81c49qeum9jaap6j7obin3o4a"
    private const val REDIR = "$SCHEME:/oauth2redirect"
    private const val FILE = "waqfa-backup.json"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)

    fun authUrl(st: Store): String {
        val raw = ByteArray(40); SecureRandom().nextBytes(raw)
        val verifier = b64(raw)
        st.pkce = verifier
        val ch = b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        return "https://accounts.google.com/o/oauth2/v2/auth?client_id=" + enc(CID) + "&redirect_uri=" + enc(REDIR) +
            "&response_type=code&scope=" + enc("openid email https://www.googleapis.com/auth/drive.appdata") +
            "&code_challenge=" + ch + "&code_challenge_method=S256&access_type=offline&prompt=consent"
    }

    private class Resp(val code: Int, val body: String)
    private fun call(method: String, url: String, token: String?, body: ByteArray?, ctype: String?, override: String? = null): Resp {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000; c.readTimeout = 30000
        c.requestMethod = if (override != null) "POST" else method
        if (override != null) c.setRequestProperty("X-HTTP-Method-Override", override)
        if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) { c.doOutput = true; if (ctype != null) c.setRequestProperty("Content-Type", ctype); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val st = if (code in 200..299) c.inputStream else c.errorStream
        val t = st?.bufferedReader()?.use { it.readText() } ?: ""
        return Resp(code, t)
    }

    private fun tokenCall(form: String): Resp = call("POST", "https://oauth2.googleapis.com/token", null, form.toByteArray(), "application/x-www-form-urlencoded")

    /** returns error text or null */
    fun exchange(st: Store, code: String): String? {
        return try {
            val r = tokenCall("client_id=" + enc(CID) + "&code=" + enc(code) + "&code_verifier=" + enc(st.pkce) +
                "&redirect_uri=" + enc(REDIR) + "&grant_type=authorization_code")
            if (r.code != 200) return "فشل تسجيل الدخول (${r.code}) " + errShort(r.body)
            val o = JSONObject(r.body)
            val rt = o.optString("refresh_token", "")
            if (rt.isEmpty()) return "جوجل لم يرجع إذن دائم"
            st.refreshToken = rt
            val idt = o.optString("id_token", "")
            if (idt.contains(".")) {
                val pl = String(Base64.decode(idt.split(".")[1], Base64.URL_SAFE), Charsets.UTF_8)
                st.email = JSONObject(pl).optString("email", "")
            }
            null
        } catch (e: Throwable) { "تعذر الاتصال: " + e.javaClass.simpleName }
    }

    private fun errShort(b: String): String = try { JSONObject(b).optString("error_description", JSONObject(b).optString("error", "")) } catch (_: Exception) { "" }

    private fun access(st: Store): String {
        if (st.refreshToken.isEmpty()) throw IllegalStateException("لم تسجل الدخول")
        val r = tokenCall("client_id=" + enc(CID) + "&refresh_token=" + enc(st.refreshToken) + "&grant_type=refresh_token")
        if (r.code != 200) {
            if (r.body.contains("invalid_grant")) { st.refreshToken = ""; st.email = ""; throw IllegalStateException("انتهت صلاحية الدخول، سجّل الدخول من جديد") }
            throw IllegalStateException("خطأ الدخول ${r.code}")
        }
        return JSONObject(r.body).getString("access_token")
    }

    private fun findId(tok: String): String? {
        val q = enc("name='$FILE'")
        val r = call("GET", "https://www.googleapis.com/drive/v3/files?spaces=appDataFolder&q=$q&fields=files(id)", tok, null, null)
        if (r.code != 200) throw IllegalStateException("درايف ${r.code}: " + errShort(r.body))
        val a = JSONObject(r.body).getJSONArray("files")
        return if (a.length() > 0) a.getJSONObject(0).getString("id") else null
    }

    /** returns error text or null */
    fun backup(st: Store): String? {
        return try {
            val tok = access(st)
            val id = findId(tok)
            val data = st.exportJson()
            val r = if (id == null) {
                val bnd = "wqf" + System.currentTimeMillis()
                val body = "--$bnd\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{\"name\":\"$FILE\",\"parents\":[\"appDataFolder\"]}\r\n" +
                    "--$bnd\r\nContent-Type: application/json\r\n\r\n$data\r\n--$bnd--"
                call("POST", "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart", tok, body.toByteArray(), "multipart/related; boundary=$bnd")
            } else call("POST", "https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media", tok, data.toByteArray(), "application/json", "PATCH")
            if (r.code !in 200..299) return "فشل النسخ (${r.code}) " + errShort(r.body)
            st.lastBackup = System.currentTimeMillis(); null
        } catch (e: IllegalStateException) { e.message } catch (e: Throwable) { "تعذر الاتصال: " + e.javaClass.simpleName }
    }

    fun restore(st: Store): String? {
        return try {
            val tok = access(st)
            val id = findId(tok) ?: return "لا توجد نسخة في درايف"
            val r = call("GET", "https://www.googleapis.com/drive/v3/files/$id?alt=media", tok, null, null)
            if (r.code != 200) return "فشل الاستعادة (${r.code})"
            st.importJson(r.body); null
        } catch (e: IllegalStateException) { e.message } catch (e: Throwable) { "تعذر الاتصال: " + e.javaClass.simpleName }
    }
}
