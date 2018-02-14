
import com.sun.net.httpserver.HttpExchange
import java.io.InputStream
import java.net.HttpCookie
import java.net.URLDecoder
import java.util.*

private val requestTextMap = Collections.synchronizedMap(WeakHashMap<HttpExchange, String>())

fun HttpExchange.requirePost() {
    requireMethod("POST")
}

fun HttpExchange.requireGet() {
    requireMethod("GET")
}

private fun HttpExchange.requireMethod(method: String, error: String = "Invalid method, required $method") {
    if (requestMethod != method) {
        sendInvalidMethod(text = error)
        throw AssertionError(error)
    }
}

fun HttpExchange.requireParameter(parameter: String, error: String = "Must have parameter $parameter") {
    if (!hasParameter(parameter)) {
        sendMalformed(text = error)
        throw IllegalArgumentException(error)
    }
}

fun HttpExchange.parameter(name: String): String {
    return parameterMap()[name] ?: throw IllegalArgumentException("Exchange does not have parameter $name")
}

fun HttpExchange.hasParameter(name: String): Boolean {
    return parameterMap().containsKey(name)
}

fun HttpExchange.sendSuccess(fileName: String? = null, text: String? = null) {
    sendResponse(200, fileName, text)
}

fun HttpExchange.sendMalformed(fileName: String? = null, text: String? = null) {
    sendResponse(400, fileName, text)
}

fun HttpExchange.sendUnauthorized(fileName: String? = null, text: String? = null) {
    sendResponse(401, fileName, text)
}

fun HttpExchange.sendNotFound(fileName: String? = null, text: String? = null) {
    sendResponse(404, fileName, text)
}

fun HttpExchange.sendInvalidMethod(fileName: String? = null, text: String? = null) {
    sendResponse(405, fileName, text)
}

fun HttpExchange.sendError(fileName: String? = null, text: String? = null) {
    sendResponse(500, fileName, text)
}

fun HttpExchange.writeCookie(name: String, value: String, maxAge: Int) {
    responseHeaders.add("Set-Cookie", "$name=$value; Max-Age=$maxAge; SameSite=Strict; Path=/")
}

fun HttpExchange.getCookies(): List<HttpCookie> {
    return if (requestHeaders.containsKey("Cookie")) HttpCookie.parse(requestHeaders.getFirst("Cookie").replace("; ", ", ")) else emptyList()
}

fun HttpExchange.getCookie(name: String): String? {
    return getCookies().firstOrNull { it.name == name }?.value
}

fun HttpExchange.getToken(): String? {
    return getCookie("tmsacoin-session")
}

fun HttpExchange.getUser(): String? {
    val token = getToken()
    return if (token != null) DBHandler.getSessionUser(token) else null
}

private fun HttpExchange.sendResponse(code: Int, fileName: String? = null, text: String? = null) {

    val response = readText(fileName, text).toByteArray(charset = Charsets.UTF_8)
    sendResponseHeaders(code, response.size.toLong())
    responseBody.write(response)
}

private fun readHtmlFile(fileName: String) = WebHandler::class.java.classLoader.getResource(fileName).readText(Charsets.UTF_8)

private fun HttpExchange.text(): String {
    if (!requestTextMap.containsKey(this)) requestTextMap[this] = requestBody.text()
    return requestTextMap[this]!!
}

private fun InputStream.text(): String {
    return this.reader().readText()
}

private fun String.asParameterMap(): Map<String, String> {
    //language=RegExp
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
