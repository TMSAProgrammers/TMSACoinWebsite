
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.io.InputStream
import java.net.URLDecoder
import java.nio.charset.Charset

internal class WebHandler : HttpHandler {
    private final val charset: Charset = Charsets.UTF_8

    override fun handle(t: HttpExchange) {
        println(t.requestURI.path)
        when (t.requestURI.path) {
            "/tmsacoin" -> handleIndex(t)
            "/tmsacoin/create" -> handleCreateForm(t)
            "/tmsacoin/internal/create" -> handleCreateRequest(t)
        }
    }

    private fun handleIndex(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "index.html")
    }

    private fun handleCreateForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "create.html")
    }

    private fun handleCreateRequest(httpExchange: HttpExchange) {
        if (httpExchange.requestMethod != "POST") httpExchange.sendInvalidMethod()
        val parameters = httpExchange.parameterMap()
        if (!parameters.containsKey("username") || !parameters.containsKey("password")) {
            httpExchange.sendMalformed(text = "Must have username and password fields")
            return
        }
        val createResult = DBHandler.createUser(parameters["username"]!!, parameters["password"]!!)
        if (createResult.first) {
            httpExchange.sendSuccess(text = createResult.second)
        } else
            httpExchange.sendError(text = createResult.second)

    }

    private fun HttpExchange.sendSuccess(fileName: String? = null, text: String? = null) {
        sendResponse(200, fileName, text)
    }

    private fun HttpExchange.sendMalformed(fileName: String? = null, text: String? = null) {
        sendResponse(400, fileName, text)
    }

    private fun HttpExchange.sendUnauthorized(fileName: String? = null, text: String? = null) {
        sendResponse(401, fileName, text)
    }

    private fun HttpExchange.sendInvalidMethod(fileName: String? = null, text: String? = null) {
        sendResponse(405, fileName, text)
    }

    private fun HttpExchange.sendError(fileName: String? = null, text: String? = null) {
        sendResponse(500, fileName, text)
    }

    private fun HttpExchange.sendResponse(code: Int, fileName: String? = null, text: String? = null) {

        val response = readText(fileName, text).toByteArray(charset = Charsets.UTF_8)
        sendResponseHeaders(code, response.size.toLong())
        responseBody.write(response)
    }

    private fun readHtmlFile(fileName: String) = javaClass.classLoader.getResource(fileName).readText(charset)

    private fun HttpExchange.text(): String {
        return this.requestBody.text()
    }

    private fun InputStream.text(): String {
        return this.reader().readText()
    }

    private fun String.asParameterMap(): Map<String, String> {
        return this.split("(?<!(\\\\)*\\)&").map { it.split("(?<!(\\\\)*\\)=") }.map { it[0] to it[1] }.toMap()
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
}