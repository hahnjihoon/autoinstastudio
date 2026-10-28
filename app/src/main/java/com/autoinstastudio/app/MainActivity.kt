package com.autoinstastudio.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private val Purple = Color(0xFF6954DC)
private val Ink = Color(0xFF24213C)
private val Muted = Color(0xFF858399)
private val Paper = Color(0xFFF7F8FC)
private val Line = Color(0xFFEBEAF3)
private val Green = Color(0xFF357D60)
private val Red = Color(0xFFB55248)

private enum class PostStatus(val title: String) {
    QUEUED("시작 대기"), GENERATING("사진 생성 중"), PUBLISHING("피드 게시 중"),
    SUBMITTED("게시 요청 완료"), PUBLISHED("게시 완료"), FAILED("오류"), STOPPED("중지됨"), REVIEW("게시 요청 중단")
}

private data class AppSettings(val prompt: String = "", val username: String = "", val accountId: String = "", val connected: Boolean = false)

private data class PhotoPost(
    val id: String = UUID.randomUUID().toString(), val prompt: String = "", val accountId: String = "",
    val title: String = "새로운 피드 사진", val caption: String = "", val imagePrompt: String = "", val image: String = "", val alt: String = "",
    val status: PostStatus = PostStatus.QUEUED, val configureStarted: Boolean = false, val remoteId: String = "",
    val createdAt: Long = System.currentTimeMillis(), val touchedAt: Long = System.currentTimeMillis(), val publishedAt: Long = 0, val submittedAt: Long = 0, val error: String = ""
) {
    fun json() = JSONObject().apply {
        put("id", id); put("prompt", prompt); put("accountId", accountId); put("title", title); put("caption", caption)
        put("imagePrompt", imagePrompt); put("image", image); put("alt", alt); put("status", status.name)
        put("configureStarted", configureStarted); put("remoteId", remoteId); put("createdAt", createdAt)
        put("touchedAt", touchedAt); put("publishedAt", publishedAt); put("submittedAt", submittedAt); put("error", error)
    }
}

private class LocalStore private constructor(context: Context) : SQLiteOpenHelper(context, "oin.db", null, 1) {
    val app = context.applicationContext
    val mediaDir = File(app.filesDir, "media").apply { mkdirs() }
    private val prefs = app.getSharedPreferences("oin_settings", Context.MODE_PRIVATE)
    private val secrets = app.getSharedPreferences("oin_credentials", Context.MODE_PRIVATE)
    val changes = MutableStateFlow(0L)
    companion object {
        @Volatile private var instance: LocalStore? = null
        fun get(context: Context): LocalStore = instance ?: synchronized(this) { instance ?: LocalStore(context).also { instance = it } }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE posts (id TEXT PRIMARY KEY, body TEXT NOT NULL)")
        db.execSQL("CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, message TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    @Synchronized fun settings(): AppSettings {
        val j = JSONObject(prefs.getString("settings", "{}") ?: "{}")
        return AppSettings(j.optString("prompt"), j.optString("username"), j.optString("accountId"), j.optBoolean("connected"))
    }
    @Synchronized fun changeSettings(change: (AppSettings) -> AppSettings) {
        val value = change(settings())
        val j = JSONObject().put("prompt", value.prompt).put("username", value.username).put("accountId", value.accountId).put("connected", value.connected)
        check(prefs.edit().putString("settings", j.toString()).commit()) { "세팅을 저장하지 못했습니다." }
        changes.value += 1
    }

    @Synchronized fun hasSecret(name: String) = secrets.contains(name)

    @Synchronized fun secret(name: String): String {
        val encoded = secrets.getString(name, null) ?: return ""
        try {
            val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = vault.getKey("oin_device_key", null) as SecretKey
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IOException("휴대폰 암호화 정보를 읽지 못했습니다. 설정에서 키 또는 계정을 다시 연결해 주세요.")
        }
    }

    @Synchronized fun putSecret(name: String, value: String) {
        if (value.isBlank()) {
            check(secrets.edit().remove(name).commit()) { "인증 정보를 삭제하지 못했습니다." }
        } else {
            val vault = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val key = if (vault.containsAlias("oin_device_key")) vault.getKey("oin_device_key", null) as SecretKey else {
                KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                    init(KeyGenParameterSpec.Builder("oin_device_key", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
                }.generateKey()
            }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val encrypted = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
            check(secrets.edit().putString(name, Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "인증 정보를 저장하지 못했습니다." }
        }
        changes.value += 1
    }

    @Synchronized fun posts(): List<PhotoPost> {
        val result = mutableListOf<PhotoPost>()
        readableDatabase.rawQuery("SELECT body FROM posts ORDER BY rowid DESC", null).use { cursor ->
            while (cursor.moveToNext()) {
                val j = JSONObject(cursor.getString(0))
                result += PhotoPost(id = j.getString("id"), prompt = j.optString("prompt"), accountId = j.optString("accountId"), title = j.optString("title"),
                    caption = j.optString("caption"), imagePrompt = j.optString("imagePrompt"), image = j.optString("image"), alt = j.optString("alt"),
                    status = PostStatus.entries.firstOrNull { it.name == j.optString("status") } ?: PostStatus.FAILED,
                    configureStarted = j.optBoolean("configureStarted"), remoteId = j.optString("remoteId"), createdAt = j.optLong("createdAt"),
                    touchedAt = j.optLong("touchedAt"), publishedAt = j.optLong("publishedAt"), submittedAt = j.optLong("submittedAt"), error = j.optString("error"))
            }
        }
        return result
    }
    @Synchronized fun save(post: PhotoPost) {
        val value = post.copy(touchedAt = System.currentTimeMillis())
        check(writableDatabase.insertWithOnConflict("posts", null, ContentValues().apply { put("id", value.id); put("body", value.json().toString()) }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "콘텐츠를 저장하지 못했습니다." }
        changes.value += 1
    }
    @Synchronized fun update(id: String, change: (PhotoPost) -> PhotoPost) {
        posts().firstOrNull { it.id == id }?.let { save(change(it)) }
    }
    fun media(name: String): File {
        require(name.isNotBlank() && name == File(name).name && name !in listOf(".", "..")) { "잘못된 사진 경로입니다." }
        return File(mediaDir, name)
    }
    @Synchronized fun event(message: String) {
        writableDatabase.insertOrThrow("events", null, ContentValues().apply { put("at", System.currentTimeMillis()); put("message", message.take(700)) })
        writableDatabase.execSQL("DELETE FROM events WHERE id NOT IN (SELECT id FROM events ORDER BY id DESC LIMIT 100)")
        print("작업 처리하는 단계 :: $message\n")
        changes.value += 1
    }
    @Synchronized fun events(): List<Pair<Long, String>> {
        val result = mutableListOf<Pair<Long, String>>()
        readableDatabase.rawQuery("SELECT at,message FROM events ORDER BY id DESC LIMIT 20", null).use { cursor -> while (cursor.moveToNext()) result += cursor.getLong(0) to cursor.getString(1) }
        return result
    }
}

private val SystemPrompt = """
    사용자가 입력한 프롬프트의 요청을 수행하여 인스타그램 피드 이미지 한 장을 생성할 프롬프트와 그 이미지에 함께 게시할 최종 설명글을 작성하세요.
    사용자 프롬프트는 완성할 콘텐츠에 대한 요청입니다. 요청을 요약하거나 평가하지 말고, 이미지 기획안·제작 가이드·템플릿 운영 제안으로 바꾸지 마세요.
    콘텐츠의 소재·인물·복장·배경·구도·표현 스타일과 설명글의 내용은 사용자 요청을 충실히 따르세요. 요청하지 않은 장면이나 묘사를 임의로 덧붙이지 마세요.
    사용자가 여러 항목별 내용을 요구하면 각 항목의 실제 내용을 구체적으로 완성하세요. 항목 이름이나 작성 요령만 나열하지 마세요.
    image_prompt는 이미지 생성기에 전달할 상세 영어 프롬프트입니다. 세로 4:5 이미지 한 장에 사용자 요청의 실제 콘텐츠가 완성되어 나타나도록 작성하세요.
    사용자가 매번 지정하지 않아도 인스타그램 피드 게시용 이미지로 구성하세요. 업로드와 미리보기에서 가장자리가 잘릴 가능성을 고려하여 주요 콘텐츠를 중앙에 배치하세요.
    좌우 각각 이미지 너비의 10% 이상, 상하 각각 이미지 높이의 15% 이상을 안전 여백으로 확보하세요. 주요 피사체 전체와 제목·본문·표·그리드 등 필수 콘텐츠는 이 여백을 제외한 중앙 영역 안에 모두 들어가야 합니다.
    특히 그림의 아래쪽 끝, 하단 문구와 마지막 행은 이미지 높이의 85% 지점보다 위에서 끝나야 합니다. 콘텐츠가 많으면 배치와 간격을 조정하여 안전 영역 안에 완전히 넣고, 그림이나 글자가 가장자리에 닿거나 잘린 구도로 만들지 마세요.
    배경은 캔버스 가장자리까지 자연스럽게 이어도 되지만 필수 콘텐츠는 중앙 정사각형으로 잘라 보더라도 보존되도록 구성하세요. 안전 영역을 표시하는 선·안내문·치수는 이미지에 넣지 마세요.
    위의 인스타그램용 세로 4:5 비율, 좌우 10%·상하 15% 안전 여백, 중앙 배치와 하단 잘림 방지 조건을 image_prompt에 영어로 반드시 명시하세요.
    이미지 안에 글이 필요한 요청이라면 실제로 들어갈 완성된 문구와 항목별 내용을 image_prompt에 포함하세요. 이미지에 표시할 글은 사용자가 요청한 언어 그대로 명시하세요.
    사용자가 빈 양식이나 템플릿을 명시적으로 요청하지 않았다면 예시 문구·내용 입력 안내·YYYY.MM.DD 같은 자리표시자를 넣지 마세요. 확인할 수 없는 날짜나 사실을 임의로 채우지 마세요.
    description은 이미지와 함께 인스타그램 독자에게 그대로 보여줄 최종 게시글 본문입니다. 사용자 요청에 맞는 실제 내용과 이미지에 담을 내용을 설명하세요.
    description에 이미지 생성용 프롬프트나 사용자 프롬프트를 옮기지 마세요. 사용자가 명시적으로 요청하지 않은 제작 의도·레이아웃 해설·디자인 지침·제작 과정·활용 방법을 작성하지 마세요.
    image_prompt와 description은 동일한 완성 콘텐츠를 다뤄야 하며, 항목별 내용과 이미지에 표시할 문구가 서로 모순되지 않아야 합니다.
    description에는 해시태그를 작성하지 마세요. 사용자가 지정한 언어·말투·길이를 따르며, 언어 지정이 없으면 사용자 프롬프트의 언어를 사용하세요. 전체 2,200자를 넘기지 마세요.
    확인되지 않은 사실·제품 효과·가격·후기는 만들어 내지 마세요. 사용자가 요구한 창작 콘텐츠는 요청에 맞게 완성하세요.
    반환 전에 description이 작성 지시나 기획안이 아닌 실제 게시글 본문인지 확인하고, 기획 설명이 남아 있다면 최종 콘텐츠로 다시 작성하세요.
    image_prompt와 description 두 문자열 항목만 포함한 JSON 객체를 반환하세요.
""".trimIndent()

class PublishWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    companion object { private val gate = Mutex() }
    override suspend fun getForegroundInfo(): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel("oin-work", "오인 스튜디오 생성 및 게시", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getActivity(applicationContext, 1, Intent(applicationContext, MainActivity::class.java).putExtra("stop_work", true), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, "oin-work").setSmallIcon(R.drawable.ic_oin)
            .setContentTitle("오인 스튜디오 실행 중").setContentText("사진 생성부터 피드 게시까지 진행합니다.").setOngoing(true).addAction(0, "작업 중지", stop).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(1, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC) else ForegroundInfo(1, notification)
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        gate.withLock {
            val store = LocalStore.get(applicationContext)
            val postId = inputData.getString("post").orEmpty()
            var phase = "실행 조건 확인 중"
            var phaseAt = System.currentTimeMillis()
            val startedAt = phaseAt
            val progressGate = Mutex()
            val report: suspend (String) -> Unit = { message ->
                progressGate.withLock {
                    if (phase != message) { phase = message; phaseAt = System.currentTimeMillis(); store.event(message) }
                    setProgress(workDataOf("phase" to phase, "phaseAt" to phaseAt, "startedAt" to startedAt, "at" to System.currentTimeMillis()))
                }
            }
            val heartbeat = CoroutineScope(currentCoroutineContext()).launch {
                while (true) {
                    progressGate.withLock { setProgress(workDataOf("phase" to phase, "phaseAt" to phaseAt, "startedAt" to startedAt, "at" to System.currentTimeMillis())) }
                    delay(2000)
                }
            }
            try {
                setForeground(getForegroundInfo())
                var post = store.posts().firstOrNull { it.id == postId } ?: throw IOException("실행할 콘텐츠가 없습니다.")
                if (post.status in listOf(PostStatus.SUBMITTED, PostStatus.PUBLISHED)) return@withLock Result.success(workDataOf("phase" to "이미 게시 요청을 전송한 콘텐츠입니다.", "at" to System.currentTimeMillis()))
                check(!post.configureStarted) { "이미 게시 버튼을 누른 콘텐츠입니다. 같은 사진을 자동으로 다시 전송하지 않습니다." }
                val settings = store.settings()
                require(post.prompt.isNotBlank()) { "세팅에서 프롬프트를 입력해 주세요." }
                require(store.hasSecret("openai")) { "세팅에서 OpenAI API 키를 저장해 주세요." }
                require(settings.connected && settings.accountId == post.accountId) { "게시할 인스타그램 계정을 연결해 주세요." }
                val service = InstagramAutomationService.active ?: throw IOException("세팅에서 인스타그램 화면 조작 권한을 켜 주세요.")
                if (post.caption.isBlank() || post.imagePrompt.isBlank()) {
                    store.update(postId) { it.copy(status = PostStatus.GENERATING, error = "") }
                    report("GPT · 사용자 프롬프트로 사진과 설명 생성 중")
                    val names = listOf("image_prompt", "description")
                    val schema = JSONObject().put("type", "object").put("properties", JSONObject().apply { names.forEach { put(it, JSONObject().put("type", "string")) } })
                        .put("required", JSONArray(names)).put("additionalProperties", false)
                    val response = OpenAi(store).request("responses", JSONObject().put("model", "gpt-5.2").put("store", false)
                        .put("instructions", SystemPrompt).put("input", post.prompt)
                        .put("text", JSONObject().put("format", JSONObject().put("type", "json_schema").put("name", "feed_photo").put("strict", true).put("schema", schema))))
                    val generated = StringBuilder()
                    val output = response.optJSONArray("output") ?: JSONArray()
                    for (index in 0 until output.length()) {
                        val parts = output.getJSONObject(index).optJSONArray("content") ?: continue
                        for (partIndex in 0 until parts.length()) {
                            val part = parts.getJSONObject(partIndex)
                            if (part.optString("type") == "refusal") throw IOException("GPT 기획 요청 거절: " + part.optString("refusal", "응답을 생성하지 못했습니다.").take(400))
                            if (part.optString("type") == "output_text") generated.append(part.getString("text"))
                        }
                    }
                    require(response.optString("status") == "completed") { "GPT 기획이 완료되지 않았습니다: ${response.optJSONObject("incomplete_details")?.optString("reason").orEmpty()}" }
                    val plan = JSONObject(generated.toString())
                    require(plan.getString("description").trim().length in 1..2200) { "생성된 사진 설명의 길이를 확인해 주세요." }
                    require(plan.getString("image_prompt").isNotBlank()) { "사진 생성 프롬프트가 비어 있습니다." }
                    post = post.copy(caption = plan.getString("description").trim(), imagePrompt = plan.getString("image_prompt"), alt = plan.getString("description").trim(), status = PostStatus.GENERATING, error = "")
                    store.save(post)
                }
                val description = post.caption.replace(Regex("(?<!\\S)[#＃][\\p{L}\\p{M}\\p{N}_]+"), "").replace(Regex("(?m)[ \\t]+$"), "").trim()
                require(description.length in 1..2200) { "해시태그를 제외한 사진 설명이 필요합니다." }
                if (description != post.caption) { post = post.copy(caption = description, alt = description); store.save(post) }
                val filename = post.image.ifBlank { "$postId.jpg" }
                val file = store.media(filename)
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                if (file.isFile) BitmapFactory.decodeFile(file.absolutePath, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                    report("사진 생성 중 · 이미지 API 응답 대기")
                    val image = OpenAi(store).request("images/generations", JSONObject().put("model", "gpt-image-2.5-sunburst")
                        .put("prompt", post.imagePrompt).put("moderation", "low").put("size", "1024x1280").put("n", 1).put("quality", "medium").put("output_format", "jpeg"))
                    currentCoroutineContext().ensureActive()
                    val bytes = Base64.decode(image.getJSONArray("data").getJSONObject(0).getString("b64_json"), Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("생성된 사진을 읽지 못했습니다.")
                    val temporary = store.media("$postId.part")
                    try {
                        temporary.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it)) { "사진 저장에 실패했습니다." } }
                        check(temporary.renameTo(file)) { "사진 파일을 저장하지 못했습니다." }
                    } finally { bitmap.recycle(); temporary.delete() }
                }
                store.update(postId) { it.copy(image = filename, status = PostStatus.PUBLISHING, error = "") }
                currentCoroutineContext().ensureActive()
                report("사진 생성 완료 · 인스타그램 피드 게시 시작")
                service.execute(postId, report)
                report("사진·설명 입력 및 피드 게시 요청 완료")
                Result.success(workDataOf("outcome" to "DONE", "phase" to "사진과 설명을 넣고 피드 게시 버튼을 눌렀습니다.", "at" to System.currentTimeMillis()))
            } catch (cancel: CancellationException) {
                store.update(postId) { if (it.status in listOf(PostStatus.SUBMITTED, PostStatus.PUBLISHED)) it else it.copy(status = if (it.configureStarted) PostStatus.REVIEW else PostStatus.STOPPED, error = if (it.configureStarted) "게시 요청 중 작업이 중지되었습니다. 같은 사진을 자동으로 다시 보내지 않습니다." else "작업을 중지했습니다.") }
                throw cancel
            } catch (error: Exception) {
                val message = error.message.orEmpty().ifBlank { "작업을 완료하지 못했습니다." }.take(700)
                store.update(postId) { it.copy(status = if (it.configureStarted) PostStatus.REVIEW else PostStatus.FAILED, error = message) }
                store.event(message)
                Result.success(workDataOf("outcome" to "ERROR", "phase" to message, "at" to System.currentTimeMillis()))
            } finally { heartbeat.cancel() }
        }
    }
}

private class OpenAi(private val store: LocalStore) {
    suspend fun request(path: String, body: JSONObject? = null): JSONObject = suspendCancellableCoroutine { continuation ->
        val key = store.secret("openai")
        require(key.isNotBlank()) { "설정에서 OpenAI API 키를 입력해 주세요." }
        val connection = URL("https://api.openai.com/v1/$path").openConnection() as HttpURLConnection
        continuation.invokeOnCancellation { connection.disconnect() }
        Thread({
            try {
                if (!continuation.isActive) return@Thread
                connection.instanceFollowRedirects = false
                connection.connectTimeout = 30_000
                connection.readTimeout = 240_000
                connection.requestMethod = if (body == null) "GET" else "POST"
                connection.setRequestProperty("Authorization", "Bearer $key")
                connection.setRequestProperty("Accept", "application/json")
                if (body != null) {
                    connection.doOutput = true
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                }
                val code = connection.responseCode
                val text = (if (code in 200..299) connection.inputStream else connection.errorStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                val json = runCatching { JSONObject(text) }.getOrElse { throw IOException("OpenAI 응답을 읽지 못했습니다. HTTP $code") }
                if (code !in 200..299) {
                    val failure = json.optJSONObject("error") ?: JSONObject()
                    val detail = failure.optString("message").replace(key, "숨김").take(450)
                    val category = failure.optJSONObject("moderation_details")
                    val location = if (path == "responses") "콘텐츠 기획" else "사진 생성"
                    val reason = listOf(failure.optString("code"), category?.optString("moderation_stage").orEmpty(), category?.optJSONArray("categories")?.join(",").orEmpty()).filter { it.isNotBlank() }.joinToString(" / ")
                    throw IOException("$location API 오류 ($code${if (reason.isBlank()) "" else " / $reason"}): $detail")
                }
                if (continuation.isActive) continuation.resume(json)
            } catch (error: Exception) {
                if (continuation.isActive) continuation.resumeWithException(error)
            } finally { connection.disconnect() }
        }, "oin-openai").start()
    }
}


class InstagramAutomationService : AccessibilityService() {
    companion object {
        @Volatile var active: InstagramAutomationService? = null
            private set
    }
    private val gate = Mutex()
    @Volatile private var stopping = 0L
    override fun onServiceConnected() { active = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    fun stopPosting() { stopping += 1 }
    override fun onInterrupt() = Unit
    override fun onDestroy() { stopping += 1; active = null; super.onDestroy() }

    suspend fun execute(postId: String = "", onStatus: suspend (String) -> Unit = {}) = gate.withLock {
        withContext(Dispatchers.Main) {
            val store = LocalStore.get(this@InstagramAutomationService)
            val settings = store.settings()
            val username = settings.username.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
            require(username.matches(Regex("[A-Za-z0-9._]{1,30}"))) { "인스타그램 아이디를 저장해 주세요." }
            check(active === this@InstagramAutomationService) { "화면 조작 권한이 꺼져 있습니다." }
            try { packageManager.getPackageInfo("com.instagram.android", 0) }
            catch (_: PackageManager.NameNotFoundException) { throw IOException("휴대폰에 인스타그램 앱을 설치해 주세요.") }
            val post = if (postId.isBlank()) null else (store.posts().firstOrNull { it.id == postId } ?: throw IOException("게시할 사진이 없습니다."))
            if (post != null) {
                require(settings.connected && settings.accountId == post.accountId) { "세팅에 연결한 인스타그램 계정을 확인해 주세요." }
                check(!post.configureStarted) { "같은 사진의 게시 요청을 다시 보내지 않습니다." }
            }
            val keyguard = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val power = getSystemService(Context.POWER_SERVICE) as PowerManager
            val epoch = stopping
            val shareIntent = if (post == null) null else {
                val file = store.media(post.image)
                require(file.isFile && file.length() > 0) { "생성된 사진이 없습니다." }
                val uri = FileProvider.getUriForFile(this@InstagramAutomationService, "$packageName.files", file)
                Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"; setPackage("com.instagram.android")
                    putExtra(Intent.EXTRA_STREAM, uri); putExtra(Intent.EXTRA_TEXT, post.caption)
                    clipData = ClipData.newRawUri("photo", uri)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            if (shareIntent != null) {
                onStatus("인스타그램 실행 · 생성한 사진 전달 중")
                startActivity(shareIntent)
            } else {
                onStatus("인스타그램 계정 연결 화면 여는 중")
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/$username/")).setPackage("com.instagram.android").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            var captionEntered = false
            var destinationChosen = false
            var lastActionScreen = ""
            var lastActionAt = System.currentTimeMillis()
            var loginRequired = false
            var loginReturnAt = 0L
            var nextCount = 0
            val manualChoices = setOf("항상", "한 번만", "이번만", "이번에만", "항상 허용", "앱 사용 중에만", "허용", "허용 안 함", "항상 공유", "이번에만 공유", "Always", "Just once", "Only this time", "While using the app", "Allow", "Don't allow")
            while (true) {
                currentCoroutineContext().ensureActive()
                check(active === this@InstagramAutomationService) { "화면 조작 권한이 해제되어 중지했습니다." }
                check(stopping == epoch) { "사용자가 작업을 중지했습니다." }
                delay(500)
                if (keyguard.isKeyguardLocked || !power.isInteractive) {
                    lastActionScreen = ""
                    onStatus("사용자 선택 대기 · 화면을 켜고 잠금을 해제하면 자동으로 이어집니다.")
                    continue
                }
                val root = rootInActiveWindow
                if (root == null || root.packageName?.toString() != "com.instagram.android") {
                    lastActionScreen = ""
                    onStatus(if (root?.packageName?.toString() == packageName) "사용자 선택 대기 · 인스타그램 화면으로 돌아가면 자동으로 이어집니다." else "사용자 선택 대기 · 연결 앱·권한·항상/한 번만 선택을 마치면 자동으로 이어집니다.")
                    continue
                }
                if (!root.refresh()) {
                    lastActionScreen = ""
                    onStatus("사용자 선택 대기 · 변경된 인스타그램 화면을 다시 읽고 있습니다.")
                    continue
                }
                val nodes = mutableListOf<AccessibilityNodeInfo>()
                val queue = java.util.ArrayDeque<AccessibilityNodeInfo>()
                queue.add(root)
                while (queue.isNotEmpty() && nodes.size < 800) {
                    val node = queue.removeFirst()
                    if (node.isVisibleToUser) nodes.add(node)
                    for (index in 0 until node.childCount) node.getChild(index)?.let { queue.add(it) }
                }
                val texts = nodes.flatMap { listOf(it.text?.toString().orEmpty(), it.contentDescription?.toString().orEmpty()) }.filter { it.isNotBlank() }
                if (nodes.any { node -> !node.isEditable && node.isEnabled && (node.isClickable || node.parent?.isClickable == true) &&
                        (node.text?.toString()?.trim() in manualChoices || node.contentDescription?.toString()?.trim() in manualChoices) }) {
                    lastActionScreen = ""
                    onStatus("사용자 선택 대기 · 인스타그램의 선택창을 직접 처리해 주세요. 선택 후 자동으로 이어집니다.")
                    continue
                }
                if (nodes.any { it.isPassword || (it.isEditable && Regex("password|login|username|security_code|confirmation_code|verification|two_factor|otp", RegexOption.IGNORE_CASE).containsMatchIn(it.viewIdResourceName.orEmpty())) }) {
                    loginRequired = true
                    loginReturnAt = 0L
                    lastActionScreen = ""
                    onStatus("사용자 선택 대기 · 인스타그램 로그인·추가 인증을 완료하면 자동으로 이어집니다.")
                    continue
                }
                val now = System.currentTimeMillis()
                val screen = root.windowId.toString() + texts.joinToString("|") + nodes.mapNotNull { it.viewIdResourceName }.joinToString("|")
                val wait = if (screen == lastActionScreen) 3500L else 1000L
                if (now - lastActionAt < wait) continue
                if (post == null) {
                    val ownProfile = texts.any { it.trim() in listOf("프로필 편집", "Edit profile", "Edit Profile") }
                    val correctAccount = texts.any { it.trim().removePrefix("@").split(Regex("[\\s,·]+")).firstOrNull()?.equals(username, ignoreCase = true) == true }
                    if (ownProfile && correctAccount) {
                        store.changeSettings { it.copy(username = username, accountId = "native:$username", connected = true) }
                        startActivity(Intent(this@InstagramAutomationService, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        return@withContext
                    }
                    val target = nodes.firstOrNull { it.isEnabled && it.viewIdResourceName.orEmpty().endsWith(":id/profile_tab") }
                    var button = target
                    var depth = 0
                    while (depth++ < 4) { val candidate = button ?: break; if (candidate.isClickable) break; button = candidate.parent }
                    val clickable = button
                    if (clickable != null && clickable.isClickable && clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        lastActionScreen = screen; lastActionAt = now
                        onStatus("인스타그램 본인 프로필 여는 중")
                    } else onStatus("사용자 선택 대기 · 로그인한 본인 프로필을 열면 계정 연결을 마칩니다.")
                    continue
                }
                val captionFields = nodes.filter { it.isEditable && !it.isPassword && Regex("caption|문구|설명", RegexOption.IGNORE_CASE).containsMatchIn(it.viewIdResourceName.orEmpty() + " " + it.hintText?.toString().orEmpty() + " " + it.text?.toString().orEmpty() + " " + it.contentDescription?.toString().orEmpty()) }
                val homeNavigation = nodes.any { it.viewIdResourceName.orEmpty().endsWith(":id/feed_tab") || it.viewIdResourceName.orEmpty().endsWith(":id/profile_tab") }
                val editorVisible = captionFields.isNotEmpty() || texts.any { Regex("^(다음|Next|새 게시물|New post|자르기|Crop|필터|Filter)$", RegexOption.IGNORE_CASE).matches(it.trim()) }
                if (loginRequired && editorVisible) loginRequired = false
                if (loginRequired && loginReturnAt == 0L) loginReturnAt = now
                if (loginRequired && homeNavigation && !editorVisible && !captionEntered && now - loginReturnAt >= 8000) {
                    startActivity(shareIntent!!)
                    loginRequired = false; loginReturnAt = 0L; destinationChosen = false; lastActionScreen = ""; lastActionAt = now
                    onStatus("로그인 완료 · 생성한 사진을 인스타그램에 전달 중")
                    continue
                }
                if (loginRequired) {
                    onStatus("사용자 선택 대기 · 로그인·추가 인증을 마치면 사진 게시로 자동으로 이어집니다.")
                    continue
                }
                if (captionFields.size == 1) {
                    val field = captionFields.single()
                    if (field.text?.toString()?.trim() != post.caption.trim()) {
                        field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val accepted = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, post.caption) })
                        lastActionScreen = screen; lastActionAt = now
                        onStatus(if (accepted) "인스타그램 사진 설명 입력 중" else "사용자 선택 대기 · 설명 입력란을 선택하면 자동으로 다시 입력합니다.")
                        continue
                    }
                    captionEntered = true
                }
                var action = ""
                var candidates = emptyList<AccessibilityNodeInfo>()
                if (captionEntered) {
                    action = "share"
                    candidates = nodes.filter { it.isEnabled && (it.text?.toString()?.trim() in listOf("공유", "Share", "게시", "Post") || it.contentDescription?.toString()?.trim() in listOf("공유", "Share", "게시", "Post")) }
                    if (candidates.isEmpty()) {
                        action = "done"
                        candidates = nodes.filter { it.isEnabled && (it.text?.toString()?.trim() in listOf("완료", "Done") || it.contentDescription?.toString()?.trim() in listOf("완료", "Done")) }
                    }
                } else if (!destinationChosen) {
                    action = "feed"
                    val destinations = if (texts.any { it.trim() in listOf("스토리", "Story", "Stories", "릴스", "Reels") }) listOf("피드", "Feed", "게시물", "Post") else listOf("피드", "Feed")
                    candidates = nodes.filter { it.isEnabled && (it.text?.toString()?.trim() in destinations || it.contentDescription?.toString()?.trim() in destinations) }
                }
                if (candidates.isEmpty() && !captionEntered) {
                    action = "caption"
                    candidates = nodes.filter { !it.isEditable && it.isEnabled && Regex("^(문구.*|설명.*|캡션.*|Write a caption.*|Add a caption.*|Add description.*)$", RegexOption.IGNORE_CASE).matches(it.text?.toString().orEmpty().ifBlank { it.contentDescription?.toString().orEmpty() }) }
                }
                if (candidates.isEmpty()) {
                    action = "next"
                    candidates = nodes.filter { it.isEnabled && (it.text?.toString()?.trim() in listOf("다음", "Next") || it.contentDescription?.toString()?.trim() in listOf("다음", "Next")) }
                }
                val buttons = candidates.mapNotNull { node ->
                    var current: AccessibilityNodeInfo? = node
                    var depth = 0
                    while (depth++ < 4) { val candidate = current ?: break; if (candidate.isClickable) break; current = candidate.parent }
                    current?.takeIf { it.isClickable && it.isEnabled }
                }.distinctBy { node -> android.graphics.Rect().also { node.getBoundsInScreen(it) }.toShortString() }
                if (buttons.size != 1) {
                    lastActionScreen = ""
                    onStatus("사용자 선택 대기 · 인스타그램 화면에서 필요한 항목을 직접 선택하면 다음 화면부터 자동으로 이어집니다.")
                    continue
                }
                val button = buttons.single()
                if (action == "share") {
                    check(stopping == epoch) { "게시 전에 작업을 중지했습니다." }
                    store.update(postId) { it.copy(configureStarted = true) }
                    if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        store.update(postId) { it.copy(status = PostStatus.SUBMITTED, submittedAt = System.currentTimeMillis(), error = "") }
                        onStatus("사진·설명 입력 및 피드 게시 요청 완료")
                        return@withContext
                    }
                    store.update(postId) { it.copy(configureStarted = false) }
                    onStatus("사용자 선택 대기 · 게시 버튼을 사용할 수 있는 화면으로 이동하면 자동으로 이어집니다.")
                } else if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    when (action) {
                        "feed" -> { destinationChosen = true; onStatus("사진 피드 선택 완료 · 게시 화면 진행 중") }
                        "caption" -> onStatus("사진 설명 입력란 여는 중")
                        "done" -> onStatus("사진 설명 입력 완료 · 게시 화면으로 이동 중")
                        else -> { destinationChosen = true; nextCount += 1; onStatus("인스타그램 다음 단계 진행 중 · $nextCount 단계") }
                    }
                } else onStatus("사용자 선택 대기 · 현재 화면을 직접 선택하면 자동으로 이어집니다.")
                lastActionScreen = screen; lastActionAt = now
            }
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = LocalStore.get(this)
        val defaults = getSharedPreferences("oin_defaults", Context.MODE_PRIVATE)
        if (!defaults.getBoolean("api", false) && BuildConfig.DEFAULT_OPENAI_API_KEY.isNotBlank()) {
            if (!store.hasSecret("openai")) store.putSecret("openai", BuildConfig.DEFAULT_OPENAI_API_KEY)
            check(defaults.edit().putBoolean("api", true).commit())
        }
        if (!defaults.getBoolean("username", false) && BuildConfig.DEFAULT_INSTAGRAM_USERNAME.isNotBlank()) {
            if (store.settings().username.isBlank()) store.changeSettings { it.copy(username = BuildConfig.DEFAULT_INSTAGRAM_USERNAME.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)) }
            check(defaults.edit().putBoolean("username", true).commit())
        }
        if (intent.getBooleanExtra("stop_work", false)) {
            InstagramAutomationService.active?.stopPosting()
            WorkManager.getInstance(this).cancelUniqueWork("oin-run")
            intent.removeExtra("stop_work")
        }
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Purple, background = Paper, surface = Color.White, onSurface = Ink, onBackground = Ink, outline = Line)) {
                OinStudio()
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("stop_work", false)) {
            InstagramAutomationService.active?.stopPosting()
            WorkManager.getInstance(this).cancelUniqueWork("oin-run")
            intent.removeExtra("stop_work")
        }
    }
}

@Composable
private fun OinStudio() {
    val context = LocalContext.current
    val store = remember { LocalStore.get(context) }
    val revision by store.changes.collectAsState()
    val settings = remember(revision) { store.settings() }
    val posts = remember(revision) { store.posts() }
    val events = remember(revision) { store.events() }
    val hasKey = remember(revision) { store.hasSecret("openai") }
    val infos by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow("oin-run") }.collectAsState(initial = emptyList())
    val currentWork = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
        ?: infos.filter { item -> item.tags.any { it.startsWith("oin-request-") } }
            .maxByOrNull { item -> item.tags.mapNotNull { it.removePrefix("oin-request-").toLongOrNull() }.maxOrNull() ?: 0L }
    val working = infos.any { !it.state.isFinished }
    val latest = posts.firstOrNull()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var starting by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    DisposableEffect(working) {
        val window = (context as? android.app.Activity)?.window
        if (working) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val stop: () -> Unit = {
        currentWork?.let { work -> scope.launch {
            try {
                InstagramAutomationService.active?.stopPosting()
                withContext(Dispatchers.IO) { WorkManager.getInstance(context).cancelWorkById(work.id).result.get() }
            } catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) { snackbar.showSnackbar(error.message ?: "작업 중지를 요청하지 못했습니다.") }
        } }
    }
    val generate: () -> Unit = {
        if (!working && !starting) {
            starting = true
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val current = store.settings()
                        check(WorkManager.getInstance(context).getWorkInfosForUniqueWork("oin-run").get().none { !it.state.isFinished }) { "이미 실행 중인 작업이 있습니다." }
                        val previous = store.posts().firstOrNull()
                        val reuse = previous != null && previous.status in listOf(PostStatus.FAILED, PostStatus.STOPPED) && !previous.configureStarted && previous.prompt == current.prompt && previous.accountId == current.accountId
                        val post = if (reuse) previous!!.copy(status = PostStatus.QUEUED, error = "") else PhotoPost(prompt = current.prompt, accountId = current.accountId)
                        val request = OneTimeWorkRequestBuilder<PublishWorker>().setInputData(workDataOf("post" to post.id))
                            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                            .addTag("oin-request-${System.currentTimeMillis()}").build()
                        store.save(post)
                        try { WorkManager.getInstance(context).enqueueUniqueWork("oin-run", ExistingWorkPolicy.KEEP, request).result.get() }
                        catch (error: Exception) { store.update(post.id) { it.copy(status = PostStatus.FAILED, error = "작업 시작 실패: ${error.message.orEmpty().take(200)}") }; throw error }
                    }
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { snackbar.showSnackbar(error.message ?: "작업을 시작하지 못했습니다.") }
                finally { starting = false }
            }
        }
    }
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(39.dp).background(Purple, RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) { Text("O", color = Color.White, fontSize = 27.sp, fontWeight = FontWeight.Black) }
                Column(Modifier.weight(1f).padding(start = 11.dp)) {
                    Text("오인 스튜디오", color = Ink, fontWeight = FontWeight.ExtraBold, fontSize = 19.sp)
                    Text("OIN STUDIO", color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
                }
                Surface(color = if (working) Color(0xFFECE8FF) else Color.White, shape = RoundedCornerShape(20.dp)) {
                    Text(if (working && currentWork?.progress?.getString("phase")?.startsWith("사용자 선택 대기") == true) "사용자 선택 대기" else if (working) "실행 중" else "사진 · 피드", color = if (working) Purple else Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 13.dp, vertical = 8.dp))
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = Color.White, tonalElevation = 0.dp) {
                listOf("홈", "생성", "세팅").forEachIndexed { index, name ->
                    NavigationBarItem(selected = tab == index, onClick = { tab = index }, label = { Text(name, fontSize = 11.sp, fontWeight = if (tab == index) FontWeight.Bold else FontWeight.Normal) },
                        colors = NavigationBarItemDefaults.colors(selectedIconColor = Purple, selectedTextColor = Purple, indicatorColor = Color(0xFFEFECFC)),
                        icon = {
                            Canvas(Modifier.size(23.dp)) {
                                val color = if (tab == index) Purple else Muted
                                val width = size.width
                                val stroke = 1.8.dp.toPx()
                                when (index) {
                                    0 -> drawPath(Path().apply { moveTo(width * 0.12f, width * 0.45f); lineTo(width * 0.5f, width * 0.12f); lineTo(width * 0.88f, width * 0.45f); moveTo(width * 0.22f, width * 0.4f); lineTo(width * 0.22f, width * 0.87f); lineTo(width * 0.78f, width * 0.87f); lineTo(width * 0.78f, width * 0.4f); moveTo(width * 0.43f, width * 0.87f); lineTo(width * 0.43f, width * 0.62f); lineTo(width * 0.59f, width * 0.62f); lineTo(width * 0.59f, width * 0.87f) }, color, style = Stroke(stroke, cap = StrokeCap.Round))
                                    1 -> { drawCircle(color, width * 0.39f, style = Stroke(stroke)); drawLine(color, Offset(width * 0.5f, width * 0.29f), Offset(width * 0.5f, width * 0.71f), stroke, StrokeCap.Round); drawLine(color, Offset(width * 0.29f, width * 0.5f), Offset(width * 0.71f, width * 0.5f), stroke, StrokeCap.Round) }
                                    else -> for (row in 0..2) { val y = width * (0.23f + row * 0.27f); drawLine(color, Offset(width * 0.12f, y), Offset(width * 0.88f, y), stroke, StrokeCap.Round); drawCircle(color, width * 0.08f, Offset(width * if (row == 1) 0.65f else 0.35f, y)) }
                                }
                            }
                        })
                }
            }
        }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                0 -> HomePage(settings, posts, events, hasKey, currentWork, working, stop, onConnect = { connecting = true }) { tab = 2 }
                1 -> GeneratePage(store, latest, currentWork, working, starting, generate, stop, onConnect = { connecting = true })
                else -> SettingsPage(store, settings, working || starting, onConnect = { connecting = true }, onMessage = { message -> scope.launch { snackbar.showSnackbar(message) } })
            }
        }
    }
    if (connecting) ConnectDialog(store, onDismiss = { connecting = false })
}

@Composable
private fun HomePage(settings: AppSettings, posts: List<PhotoPost>, events: List<Pair<Long, String>>, hasKey: Boolean, work: WorkInfo?, working: Boolean, stop: () -> Unit, onConnect: () -> Unit, settingsPage: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Text("현재 상황", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = Ink); Text("생성부터 게시까지, 지금의 진행 상황입니다.", color = Muted, fontSize = 12.sp) }
        item {
            Surface(color = Ink, shape = RoundedCornerShape(23.dp)) {
                Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("나의 인스타그램", color = Color(0xFFC4BDDF), fontSize = 11.sp)
                    Text(if (settings.connected) "@${settings.username}" else "아직 연결되지 않았습니다", fontSize = 21.sp, color = Color.White, fontWeight = FontWeight.Bold)
                    Text(if (settings.connected) "인스타그램 앱에서 확인한 게시 계정" else "세팅에서 게시할 계정을 연결해 주세요.", color = Color(0xFFC4BDDF), fontSize = 11.sp)
                    if (!settings.connected) TextButton(onClick = settingsPage) { Text("계정 연결하기 →", color = Color(0xFFC8F6DE)) }
                }
            }
        }
        item { RunStatus(work, posts.firstOrNull(), stop, onConnect) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf("프롬프트" to if (settings.prompt.isBlank()) "입력 전" else "저장됨", "API 키" to if (hasKey) "저장됨" else "입력 전", "게시 요청" to posts.count { it.status in listOf(PostStatus.SUBMITTED, PostStatus.PUBLISHED) }.toString()).forEach { (title, value) ->
                    Surface(shape = RoundedCornerShape(17.dp), color = Color.White, modifier = Modifier.weight(1f).clickable { if (title != "게시 요청") settingsPage() }) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { Text(title, color = Muted, fontSize = 10.sp); Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
        item { Text("최근 진행 기록", color = Ink, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        if (events.isEmpty()) item { Text("생성 및 게시를 시작하면 진행 기록이 표시됩니다.", fontSize = 12.sp, color = Muted) }
        items(events.take(10), key = { "${it.first}:${it.second}" }) { (at, text) ->
            Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                Box(Modifier.padding(top = 6.dp).size(6.dp).background(Purple, CircleShape))
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(text, color = Ink, fontSize = 12.sp); Text(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MM.dd HH:mm:ss")), color = Muted, fontSize = 10.sp) }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun GeneratePage(store: LocalStore, latest: PhotoPost?, work: WorkInfo?, working: Boolean, starting: Boolean, generate: () -> Unit, stop: () -> Unit, onConnect: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("생성", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("세팅의 프롬프트로 사진과 설명을 만들고 피드 게시를 요청합니다.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        RunStatus(work, latest, stop, onConnect)
        Surface(shape = RoundedCornerShape(22.dp), color = Color.White) {
            if (latest?.image?.isNotBlank() == true) PhotoPreview(store, latest.image, latest.alt)
            else Box(Modifier.fillMaxWidth().aspectRatio(1.15f).background(Brush.verticalGradient(listOf(Color(0xFFF0EDFC), Color(0xFFE9F0F6)))), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("O", color = Purple.copy(alpha = 0.7f), fontSize = 62.sp, fontWeight = FontWeight.ExtraLight)
                    Text("여기에 생성한 사진이 표시됩니다", color = Muted, fontSize = 12.sp)
                }
            }
        }
        Button(onClick = generate, enabled = !working && !starting, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().height(58.dp)) {
            Text(if (working || starting) "실행 중" else "생성 및 게시", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        Text("사진·설명 생성 → 인스타그램 실행 → 사용자 선택 대기 → 피드 게시 요청", fontSize = 10.sp, color = Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (latest?.caption?.isNotBlank() == true) Surface(color = Color.White, shape = RoundedCornerShape(18.dp)) {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { Text("사진 설명", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Ink); SelectionContainer { Text(latest.caption, fontSize = 13.sp, color = Ink, lineHeight = 21.sp) } }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun RunStatus(work: WorkInfo?, post: PhotoPost?, stop: () -> Unit, onConnect: () -> Unit) {
    var clock by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val running = work?.state == WorkInfo.State.RUNNING
    val waiting = work?.state in listOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED)
    LaunchedEffect(work?.id, work?.state) { while (running) { clock = System.currentTimeMillis(); delay(1000) } }
    val details = if (running) work?.progress else work?.outputData
    val userWaiting = running && details?.getString("phase")?.startsWith("사용자 선택 대기") == true
    val at = details?.getLong("at", 0) ?: 0
    val stale = running && at > 0 && clock - at > 15_000
    val error = post?.status == PostStatus.REVIEW || (!running && !waiting && (details?.getString("outcome") == "ERROR" || post?.status == PostStatus.FAILED || work?.state == WorkInfo.State.FAILED))
    val title = when {
        stale -> "상태 갱신 끊김 · 확인 필요"
        userWaiting -> "사용자 선택 기다리는 중"
        running -> "실제 작업 실행 중"
        waiting -> "작업 시작 대기 중"
        post?.status in listOf(PostStatus.SUBMITTED, PostStatus.PUBLISHED) -> "피드 게시 요청 완료"
        post?.configureStarted == true -> "게시 요청 중 작업 종료"
        work?.state == WorkInfo.State.CANCELLED || post?.status == PostStatus.STOPPED -> "작업 중지됨"
        error -> "작업 오류"
        else -> "실행 준비"
    }
    val color = if (error || stale) Red else if (userWaiting) Color(0xFFA57527) else if (post?.status in listOf(PostStatus.SUBMITTED, PostStatus.PUBLISHED) && !running && !waiting) Green else Purple
    val message = when {
        waiting -> "네트워크 연결과 Android의 작업 시작을 기다리고 있습니다."
        !running && post?.error?.isNotBlank() == true -> post.error
        work?.state == WorkInfo.State.CANCELLED -> "작업이 취소되었습니다. 생성한 사진은 이 기기에 보관합니다."
        else -> details?.getString("phase") ?: if (running) "실행 조건을 확인하고 있습니다." else "생성 및 게시 버튼을 누르면 전체 과정을 시작합니다."
    }
    Surface(shape = RoundedCornerShape(19.dp), color = Color.White, modifier = Modifier.fillMaxWidth().border(1.dp, Line, RoundedCornerShape(19.dp))) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(color, CircleShape))
                Text(title, color = color, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(start = 8.dp))
                if (running || waiting) TextButton(onClick = stop) { Text("중지", color = Red, fontSize = 11.sp) }
            }
            Text(message, fontSize = 12.sp, lineHeight = 19.sp, color = Ink)
            if (error && message.contains("화면 조작 권한")) {
                OutlinedButton(onClick = onConnect, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) {
                    Text("화면 조작 권한 켜기", fontSize = 12.sp)
                }
            }
            if (running) {
                if (!stale && !userWaiting) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Purple)
                val startedAt = details?.getLong("startedAt", 0) ?: 0
                val phaseAt = details?.getLong("phaseAt", 0) ?: 0
                if (startedAt > 0) Text("전체 ${((clock - startedAt).coerceAtLeast(0) / 1000)}초 · 현재 단계 ${((clock - phaseAt).coerceAtLeast(0) / 1000)}초", fontSize = 10.sp, color = Muted)
                if (stale) Text("15초 이상 상태가 갱신되지 않아 실행 여부를 확인해야 합니다.", fontSize = 10.sp, color = Red)
                else if (userWaiting) Text("화면에서 직접 선택하시면 별도 재시작 없이 자동으로 이어집니다.", fontSize = 10.sp, color = color)
                else if (phaseAt > 0 && clock - phaseAt > 60_000) Text("현재 단계의 응답 또는 화면 전환을 기다리고 있습니다.", fontSize = 10.sp, color = Muted)
            }
            if (at > 0) Text("마지막 갱신 " + Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss")), fontSize = 9.sp, color = Muted)
        }
    }
}

@Composable
private fun PhotoPreview(store: LocalStore, filename: String, description: String) {
    val photo by produceState<Bitmap?>(null, filename) { value = withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(store.media(filename).absolutePath) }.getOrNull() } }
    if (photo != null) Image(photo!!.asImageBitmap(), description, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxWidth().aspectRatio(4f / 5f))
    else Box(Modifier.fillMaxWidth().aspectRatio(4f / 5f).background(Line), contentAlignment = Alignment.Center) { Text("사진을 불러오고 있습니다", color = Muted, fontSize = 12.sp) }
}

@Composable
private fun SettingsPage(store: LocalStore, settings: AppSettings, busy: Boolean, onConnect: () -> Unit, onMessage: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var prompt by rememberSaveable(settings.prompt) { mutableStateOf(settings.prompt) }
    var username by rememberSaveable(settings.username) { mutableStateOf(settings.username) }
    var key by remember { mutableStateOf(runCatching { store.secret("openai") }.getOrDefault("")) }
    var showKey by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    val save: (Boolean) -> Unit = { connect ->
        if (!busy && !saving) {
            saving = true
            scope.launch {
                try {
                    withContext(Dispatchers.IO) {
                        val cleaned = username.trim().removePrefix("@").lowercase(java.util.Locale.ROOT)
                        require(cleaned.isBlank() || cleaned.matches(Regex("[A-Za-z0-9._]{1,30}"))) { "인스타그램 아이디를 확인해 주세요." }
                        if (connect) require(cleaned.isNotBlank()) { "인스타그램 아이디를 입력해 주세요." }
                        store.putSecret("openai", key.trim())
                        store.changeSettings { current -> if (!current.username.equals(cleaned, ignoreCase = true)) AppSettings(prompt.trim(), cleaned) else current.copy(prompt = prompt.trim(), username = cleaned) }
                    }
                    if (connect) onConnect() else onMessage("세팅을 저장했습니다.")
                } catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { onMessage(error.message ?: "세팅을 저장하지 못했습니다.") }
                finally { saving = false }
            }
        }
    }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("세팅", color = Ink, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
        Text("이 프롬프트 하나로 사진과 설명을 만듭니다. 해시태그는 사용하지 않습니다.", color = Muted, fontSize = 12.sp)
        OutlinedTextField(prompt, { prompt = it }, label = { Text("사용자 프롬프트") },
            placeholder = { Text("원하는 사진의 인물, 의상, 배경, 구도와 사진 설명의 언어·말투를 한 번에 적어 주세요.") },
            enabled = !busy && !saving, minLines = 10, shape = RoundedCornerShape(17.dp), modifier = Modifier.fillMaxWidth())
        Surface(color = Color.White, shape = RoundedCornerShape(19.dp)) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(13.dp)) {
                Text("연결 설정", color = Ink, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                OutlinedTextField(key, { key = it.trim() }, label = { Text("OpenAI API 키", fontSize = 12.sp) }, singleLine = true, enabled = !busy && !saving,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "숨김" else "보기", fontSize = 10.sp) } }, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(username, { username = it.trim().removePrefix("@") }, label = { Text("게시할 인스타그램 아이디", fontSize = 12.sp) }, singleLine = true, enabled = !busy && !saving, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth())
                if (settings.connected) Text("@${settings.username} · 계정 확인됨", color = Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                OutlinedButton(onClick = { save(true) }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp)) { Text(if (settings.connected) "인스타그램 계정 다시 확인" else "인스타그램 계정 연결", fontSize = 12.sp) }
                Text("인스타그램 앱의 로그인 상태를 사용합니다. 사진 생성 요청은 OpenAI로 전송하며 API 사용 요금이 발생합니다.", color = Muted, fontSize = 10.sp, lineHeight = 17.sp)
            }
        }
        Button(onClick = { save(false) }, enabled = !busy && !saving, modifier = Modifier.fillMaxWidth().height(51.dp), shape = RoundedCornerShape(14.dp)) { Text(if (saving) "저장 중" else "세팅 저장", fontWeight = FontWeight.Bold) }
        Text("프롬프트와 생성한 사진·문구·진행 기록은 이 앱에 저장됩니다. API 키는 기기에서 암호화해 보관합니다.", color = Muted, fontSize = 10.sp, lineHeight = 17.sp)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ConnectDialog(store: LocalStore, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var granted by remember { mutableStateOf(InstagramAutomationService.active != null) }
    var consent by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { while (true) { granted = InstagramAutomationService.active != null; delay(1000) } }
    AlertDialog(onDismissRequest = { if (!working) onDismiss() }, title = { Text("인스타그램 계정 연결") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("설치된 인스타그램 앱에서 로그인한 계정을 확인합니다. 로그인·추가 인증은 인스타그램 앱에서 완료해 주세요.", fontSize = 12.sp)
            Text("화면 조작 권한으로 인스타그램의 화면 텍스트를 읽고, 생성 및 게시 요청에 따라 사진 첨부·문구 입력·피드 게시 버튼을 순서대로 조작합니다. 화면 내용은 외부 서버로 보내지 않습니다. 작업 중지 또는 권한 해제로 멈출 수 있습니다.", fontSize = 11.sp, lineHeight = 18.sp)
            if (!granted) Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(consent, { consent = it }); Text("화면 읽기·조작에 동의합니다.", fontSize = 12.sp) }
            Text("게시할 때 휴대폰 화면이 켜져 있고 잠금이 풀려 있어야 합니다.", fontSize = 11.sp, color = Muted)
            if (working) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = Red, fontSize = 11.sp)
        } },
        confirmButton = { TextButton(enabled = !working && (granted || consent), onClick = {
            if (!granted) context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            else scope.launch {
                working = true
                message = "인스타그램에서 로그인 후 본인 프로필을 열어 주세요."
                try { (InstagramAutomationService.active ?: throw IOException("화면 조작 권한을 켜 주세요.")).execute(onStatus = { message = it }); onDismiss() }
                catch (cancel: CancellationException) { throw cancel }
                catch (error: Exception) { message = error.message ?: "계정을 확인하지 못했습니다." }
                finally { working = false }
            }
        }) { Text(if (granted) "인스타그램 계정 확인" else "화면 조작 권한 켜기") } },
        dismissButton = { TextButton(onClick = { InstagramAutomationService.active?.stopPosting(); onDismiss() }) { Text(if (working) "연결 중지" else "닫기") } })
}

