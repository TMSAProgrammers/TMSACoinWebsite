
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import org.json.JSONObject
import java.nio.charset.Charset
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

internal class WebHandler() : HttpHandler {
    private final val charset: Charset = Charsets.UTF_8
    private final val prefix: String = ""
    private final val requestCount = AtomicInteger()

    init {
        println("WebHandler initialized")
    }

    override fun handle(httpExchange: HttpExchange) {
        thread {
            try {
                val cleanRequestPath = httpExchange.requestURI.path.replace(Regex("/$"), "").replace(Regex("/tmsacoin"), "/").replace("//", "/").replace(Regex("^$"), "/")
                println("Received request #${requestCount.incrementAndGet()}: ${httpExchange.requestMethod} ${httpExchange.requestURI.path}")
                when {
                    cleanRequestPath == "$prefix/" -> handleIndex(httpExchange)
                    cleanRequestPath == "$prefix/new_account" -> handleNewAccountForm(httpExchange)
                    cleanRequestPath == "$prefix/mint" -> handleMintForm(httpExchange)
                    cleanRequestPath == "$prefix/transfer" -> handleTransferForm(httpExchange)
                    cleanRequestPath == "$prefix/login" -> handleLoginForm(httpExchange)
                    cleanRequestPath == "$prefix/balance" -> handleBalanceRequest(httpExchange, httpExchange.getUser())
                    cleanRequestPath.matches(Regex("$prefix/balance/[^/]{1,32}")) -> handleBalanceRequest(httpExchange, cleanRequestPath.split("/").last())
                    cleanRequestPath == "$prefix/internal/new_account" -> handleNewAccountRequest(httpExchange)
                    cleanRequestPath == "$prefix/internal/mint" -> handleMintRequest(httpExchange)
                    cleanRequestPath == "$prefix/internal/transfer" -> handleTransferRequest(httpExchange)
                    cleanRequestPath == "$prefix/internal/login" -> handleLoginRequest(httpExchange)
                    cleanRequestPath == "$prefix/internal/logout" -> handleLogoutRequest(httpExchange)
                    cleanRequestPath == "$prefix/internal/check_session" -> handleSessionCheckRequest(httpExchange)
                    else -> handleNotFound(httpExchange)
                }
            } catch (e: Throwable) {
                e.printStackTrace()
                httpExchange.setResponseTypeJSON()
                val error = JSONObject().put("success", false).put("error", "internal")
                httpExchange.sendError(json = error)
            } finally {
                httpExchange.close()
            }
        }
    }

    private fun handleSessionCheckRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()
        val json = httpExchange.getJSON()
        if (json == null || !json.has("token")) {
            httpExchange.sendDefaultMalformed()
            return
        }

        httpExchange.setResponseTypeJSON()

        val token = json.getString("token")
        val sessionUser = DBHandler.getSessionUser(token)
        if (sessionUser != null) {
            httpExchange.sendSuccess(json = JSONObject().put("valid", true).put("user", sessionUser).put("admin", DBHandler.isAdmin(sessionUser)))
        } else {
            httpExchange.sendSuccess(json = JSONObject().put("valid", false))
        }
    }

    private fun handleIndex(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "index.html")
    }

    private fun handleNewAccountForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "new_account.html")
    }

    private fun handleMintForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "mint.html")
    }

    private fun handleTransferForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "transfer.html")
    }

    private fun handleLoginForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "login.html")
    }

    private fun handleBalanceRequest(httpExchange: HttpExchange, user: String?) {
        httpExchange.sendSuccess(json = JSONObject().put("balance", DBHandler.getUserBalance(user)))
        return
    }

    private fun handleNewAccountRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()

        val json = httpExchange.getJSON()

        if (json == null || !json.has("username") || !json.has("password")) {
            httpExchange.sendDefaultMalformed()
            return
        }

        val createResult = DBHandler.createUser(json.getString("username"), json.getString("password"))
        if (DBHandler.isAdmin(httpExchange.getUser()) &&
                json.has("admin") &&
                json.optBoolean("admin")) {

            DBHandler.makeAdmin(json.getString("username"))
        }
        val response = JSONObject().put("success", createResult.first)

        httpExchange.setResponseTypeJSON()

        if (createResult.first) {
            httpExchange.sendSuccess(json = response)
        } else {
            response.put("error", createResult.second)
            httpExchange.sendError(json = response)
        }
    }

    private fun handleMintRequest(httpExchange: HttpExchange) {
        if (DBHandler.isAdmin(httpExchange.getUser())) {
            httpExchange.requirePost()

            val json = httpExchange.getJSON()

            if (json == null || !json.has("recipient") || json.optFloat("amount") == Float.NaN) {
                httpExchange.sendDefaultMalformed()
                return
            }

            val storeResult = DBHandler.storeTransaction(null, json.getString("recipient"), json.getFloat("amount"))
            httpExchange.setResponseTypeJSON()
            val response = JSONObject().put("success", storeResult.first)
            if (storeResult.first) {
                httpExchange.sendSuccess(json = response)
            } else {
                response.put("error", storeResult.second)
                httpExchange.sendError(json = response)
            }
        } else {
            val response = JSONObject().put("success", false).put("error", "unauthorized")
            httpExchange.sendUnauthorized(json = response)
        }
    }

    private fun handleTransferRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()

        val json = httpExchange.getJSON()

        if (json == null || !json.has("to") || json.optFloat("amount") == Float.NaN) {
            httpExchange.sendDefaultMalformed()
            return
        }

        val storeResult = DBHandler.storeTransaction(httpExchange.getUser(), json.getString("to"), json.getFloat("amount"))
        httpExchange.setResponseTypeJSON()
        val response = JSONObject().put("success", storeResult.first)
        if (storeResult.first) {
            httpExchange.sendSuccess(json = response)
        } else {
            response.put("error", storeResult.second)
            httpExchange.sendError(json = response)
        }
    }

    private fun handleLoginRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()

        val json = httpExchange.getJSON()

        if (json == null || !json.has("username") || !json.has("password")) {
            httpExchange.sendDefaultMalformed()
            return
        }

        val username = json.getString("username")
        val password = json.getString("password")

        httpExchange.setResponseTypeJSON()
        val response = JSONObject()
        if (DBHandler.verifyPassword(username, password)) {
            val session = DBHandler.createSession(
                    username,
                    Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS))
            )
//            httpExchange.writeCookie(
//                    "tmsacoin-session",
//                    session,
//                    60 * 60 * 24 * 7
//            )
            response.put("success", true).put("session", JSONObject().put("token", session).put("max_age", 60 * 60 * 24 * 7).put("user", JSONObject().put("name", username).put("admin", DBHandler.isAdmin(username))))
            httpExchange.sendSuccess(json = response)
        } else {
            response.put("success", false)
            httpExchange.sendUnauthorized(json = response)
        }
    }

    private fun handleLogoutRequest(httpExchange: HttpExchange) {
        val token = httpExchange.getToken()

        if (token == null) {
            httpExchange.sendDefaultMalformed()
            return
        }

        DBHandler.deleteSession(token)
        httpExchange.setResponseTypeJSON()
        httpExchange.sendSuccess(json = JSONObject().put("success", "true"))
    }

    private fun handleNotFound(httpExchange: HttpExchange) {
        httpExchange.sendNotFound(text = "URL not found.")
    }
}

internal class DummyHandler: HttpHandler {
    override fun handle(httpExchange: HttpExchange) {
        println("${httpExchange.requestMethod} @ ${httpExchange.requestURI.path}")
    }

}