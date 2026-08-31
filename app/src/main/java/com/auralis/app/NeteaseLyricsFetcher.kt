package com.auralis.app

import android.content.Context
import org.json.JSONObject
import java.math.BigInteger
import java.net.URLEncoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

object NeteaseLyricsFetcher {
    private const val TAG = "NeteaseLyricsFetcher"

    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    // ============================================================
    // weapi 加密（网易云网页版自己在用的请求签名方案）
    //
    // 原来歌词走的是 GET /api/song/lyric 这个已经半废弃的明文接口——很多歌曲上
    // HTTP 照样返回 200，但业务层直接给你一个空的 lrc 字段（没有真实登录态时
    // 被风控判定，返回"假成功"），这就是"检测得到歌，歌词却拿不到"的根源。
    // 封面走的是 /api/song/detail，查的是公开元数据，没有这层限制，所以没这个问题。
    //
    // 这里换成网易云网页版真正在用的 weapi 加密接口：POST 请求体做两层 AES
    // 加密 + 一次 RSA 加密随机密钥。这套算法是公开、被大量项目逆向记录过的方案，
    // 跟这个文件里本来就有的"伪造 Cookie / deviceId / IP"是同一类"模拟正常网页
    // 客户端"的手法，不是绕过什么私有认证系统。
    //
    // ⚠️ 说明：这段加密逻辑是按公开、通用的参考实现写的，但我这边的沙盒环境访问
    // 不了 music.163.com，没法直接跑一遍验证是否真的能拿到歌词——需要哥哥编译
    // 到手机上实测。如果还是不行，大概率是 csrf_token 或者参数字段（lv/tv/kv）
    // 需要跟着网易云那边的最新改动微调。
    // ============================================================
    private const val WEAPI_AES_KEY = "0CoJUm6Qyw8W8jud"
    private const val WEAPI_AES_IV = "0102030405060708"
    private const val WEAPI_RSA_MODULUS =
        "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7"
    private const val WEAPI_RSA_PUBLIC_EXP = "010001"

    private fun aesEncryptBase64(text: String, keyStr: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(WEAPI_AES_IV.toByteArray(Charsets.UTF_8))
        )
        val encrypted = cipher.doFinal(text.toByteArray(Charsets.UTF_8))
        return android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP)
    }

    // 网易云这里用的不是标准 RSA 填充：把密钥整个反转之后直接当大数做 modPow，
    // 结果补齐到 256 个十六进制字符（对应 1024 位 RSA 模数的字节长度）
    private fun rsaEncryptSecretKey(secretKey: String): String {
        val reversed = secretKey.reversed()
        val textBigInt = BigInteger(1, reversed.toByteArray(Charsets.UTF_8))
        val modulus = BigInteger(WEAPI_RSA_MODULUS, 16)
        val exponent = BigInteger(WEAPI_RSA_PUBLIC_EXP, 16)
        val result = textBigInt.modPow(exponent, modulus)
        var hex = result.toString(16)
        if (hex.length < 256) hex = "0".repeat(256 - hex.length) + hex
        return hex
    }

    private fun randomSecretKey(length: Int = 16): String {
        val chars = "abcdef0123456789"
        val random = SecureRandom()
        return buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
    }

    /** 返回 Pair(加密后的 params, 加密后的 encSecKey)，两个都要塞进 POST 表单体里 */
    private fun buildWeapiBody(paramsJson: String): Pair<String, String> {
        val secretKey = randomSecretKey()
        val firstPass = aesEncryptBase64(paramsJson, WEAPI_AES_KEY)
        val secondPass = aesEncryptBase64(firstPass, secretKey)
        val encSecKey = rsaEncryptSecretKey(secretKey)
        return secondPass to encSecKey
    }
    // ============================================================

    // 稳定的假 deviceId：只在第一次用的时候生成，存进 SharedPreferences，之后每次请求都复用同一个值。
    // 原来的写法是"每次请求都随机生成一个新的"（意图是当"动态指纹"防跟踪），但这其实弄巧成拙：
    // 同一个 Cookie 配着一个每次都在变的 deviceId，比"从头到尾用同一个身份"更像自动化脚本在批量刷接口，
    // 风控反而更容易识别拦截——真实用户的浏览器/客户端在一个会话里 deviceId 是不会变的。
    private fun getOrCreateFakeDeviceId(prefs: android.content.SharedPreferences): String {
        return prefs.getString("netease_fake_device_id", null) ?: run {
            val id = java.util.UUID.randomUUID().toString().replace("-", "").uppercase()
            prefs.edit().putString("netease_fake_device_id", id).apply()
            id
        }
    }

    private fun buildCookie(context: Context): String {
        val prefs = context.getSharedPreferences("MusicSyncPrefs", Context.MODE_PRIVATE)
        val customCookie = prefs.getString("netease_custom_cookie", "")
        val stableDeviceId = getOrCreateFakeDeviceId(prefs)
        val defaultCookie = "os=pc; osver=Microsoft-Windows-10-Professional-build-19045-64bit; appver=3.0.1; deviceId=$stableDeviceId; _ntes_nuid=$stableDeviceId"
        return if (!customCookie.isNullOrBlank()) customCookie else defaultCookie
    }

    private fun makeNeteaseRequest(context: Context, url: String): String? {
        val finalCookie = buildCookie(context)
        val randomIp = "114.114.${(1..254).random()}.${(1..254).random()}"

        return try {
            val req = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.163.com/")
                .addHeader("Cookie", finalCookie)
                .addHeader("X-Real-IP", randomIp)
                .addHeader("X-Forwarded-For", randomIp)
                .build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) response.body?.string() else null
        } catch (e: Exception) {
            null
        }
    }

    // 歌词专用：走 weapi 加密 POST，不再用那个经常"假成功"的明文 GET 接口
    private fun makeNeteaseWeapiRequest(context: Context, path: String, params: Map<String, String>): String? {
        val finalCookie = buildCookie(context)
        val randomIp = "114.114.${(1..254).random()}.${(1..254).random()}"

        return try {
            val paramsJson = JSONObject().apply { params.forEach { (k, v) -> put(k, v) } }.toString()
            val (encParams, encSecKey) = buildWeapiBody(paramsJson)
            val formBody = FormBody.Builder()
                .add("params", encParams)
                .add("encSecKey", encSecKey)
                .build()

            val req = Request.Builder()
                .url("https://music.163.com/weapi$path?csrf_token=")
                .post(formBody)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                .addHeader("Referer", "https://music.163.com/")
                .addHeader("Cookie", finalCookie)
                .addHeader("X-Real-IP", randomIp)
                .addHeader("X-Forwarded-For", randomIp)
                .build()
            val response = client.newCall(req).execute()
            if (response.isSuccessful) response.body?.string() else {
                android.util.Log.w(TAG, "weapi 请求 HTTP 非 200: ${response.code}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "weapi 请求异常: ${e.message}")
            null
        }
    }

    fun searchCandidates(context: Context, keywords: String, durationSec: Int): List<LyricCandidate> {
        // 搜索这块哥哥反馈没问题，原样保留，没动它的请求方式
        val candidates = mutableListOf<LyricCandidate>()
        try {
            val searchUrl = "https://music.163.com/api/search/get?s=${URLEncoder.encode(keywords, "UTF-8")}&type=1&limit=10"
            val body = makeNeteaseRequest(context, searchUrl) ?: return emptyList()
            val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return emptyList()

            for (i in 0 until songs.length()) {
                val song = songs.getJSONObject(i)
                val id = song.optString("id")
                val name = song.optString("name")
                val artists = song.optJSONArray("artists")
                val artistName = if (artists != null && artists.length() > 0) artists.optJSONObject(0)?.optString("name") ?: "" else ""
                val albumName = song.optJSONObject("album")?.optString("name") ?: ""
                val duration = song.optInt("duration", 0) / 1000

                candidates.add(LyricCandidate(
                    platform = "网易云", id = id, title = name, artist = artistName, album = albumName, durationSec = duration, score = 0.0, previewLrc = ""
                ))
            }
        } catch (e: Exception) {
            // silent
        }
        return candidates
    }

    fun fetchLyric(context: Context, id: String): String? {
        return try {
            val body = makeNeteaseWeapiRequest(
                context,
                "/song/lyric",
                mapOf("id" to id, "lv" to "-1", "kv" to "-1", "tv" to "-1", "csrf_token" to "")
            ) ?: return null

            val json = JSONObject(body)
            val lrc = json.optJSONObject("lrc")?.optString("lyric")
            if (!lrc.isNullOrEmpty()) {
                lrc
            } else {
                // HTTP 200 但 lrc 字段为空——留个记录方便排查，常见于登录态无效或者
                // 加密参数被网易云那边的风控识别拦截
                android.util.Log.w(TAG, "weapi 歌词字段仍为空，原始响应: ${body.take(200)}")
                null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "weapi 歌词解析异常: ${e.message}")
            null
        }
    }
}
