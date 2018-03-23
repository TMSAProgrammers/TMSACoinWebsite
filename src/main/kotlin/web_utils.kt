
import com.sun.net.httpserver.HttpExchange
import org.json.JSONObject
import java.io.InputStream
import java.net.URLDecoder
import java.util.*

private val requestTextMap = Collections.synchronizedMap(WeakHashMap<HttpExchange, String>())

fun HttpExchange.requirePost() {
    requireMethod("POST")
}

fun HttpExchange.requireGet() {
    requireMethod("GET")
}

fun HttpExchange.setResponseTypeJSON() {
    setResponseType("application/json")
}

fun HttpExchange.setResponseType(type: String) {
    responseHeaders.add("Content-Type", type)
}

private fun HttpExchange.requireMethod(method: String, error: String = "Invalid method, required $method") {
    if (requestMethod != method) {
        sendInvalidMethod(text = error)
        throw AssertionError(error)
    }
}

fun HttpExchange.sendSuccess(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(200, fileName, text)
}

fun HttpExchange.sendDefaultMalformed() = sendMalformed(json = JSONObject().put("success", "false").put("error", "malformed"))

fun HttpExchange.sendMalformed(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(400, fileName, text)
}

fun HttpExchange.sendUnauthorized(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(401, fileName, text)
}

fun HttpExchange.sendNotFound(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(404, fileName, text)
}

fun HttpExchange.sendInvalidMethod(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(405, fileName, text)
}

fun HttpExchange.sendError(fileName: String? = null, json: JSONObject? = null, text: String? = json?.toString(0)) {
    sendResponse(500, fileName, text)
}

fun HttpExchange.getToken(): String? {
    return getJSON()?.optString("token", null)
}

fun HttpExchange.getUser(): String? {
    val token = getToken()
    return if (token != null) DBHandler.getSessionUser(token) else null
}

fun HttpExchange.getJSON(): JSONObject? {
    return try { JSONObject(text()) } catch (t: Throwable) { null }
}

private fun HttpExchange.sendResponse(code: Int, fileName: String? = null, text: String? = null) {

    val response = readText(fileName, text).toByteArray(charset = Charsets.UTF_8)
    sendResponseHeaders(code, response.size.toLong())
    responseBody.write(response)
}

private fun readHtmlFile(fileName: String) = WebHandler::class.java.classLoader.getResource(fileName).readText(Charsets.UTF_8)

fun HttpExchange.text(): String {
    if (!requestTextMap.containsKey(this)) requestTextMap[this] = requestBody.text()
    return requestTextMap[this]!!
}

private fun InputStream.text(): String {
    return this.reader().readText()
}

private fun String.asParameterMap(): Map<String, String> {
    return this.split("&").map { it.split("=") }.map { it[0] to it[1] }.toMap()
}

private fun String.urlDecoded(): String {
    return URLDecoder.decode(this, "UTF-8")
}

private fun HttpExchange.parameterMap(): Map<String, String> {
    return this.text().urlDecoded().asParameterMap()
}

private fun readText(fileName: String? = null, text: String? = null): String {
    return if (fileName != null) readHtmlFile(fileName) else text ?: ""
}

fun String.toFloatOrNaN(): Float {
    return toFloatOrNull() ?: Float.NaN
}