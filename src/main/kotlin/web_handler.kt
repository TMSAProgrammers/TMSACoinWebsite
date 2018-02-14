
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.Charset
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit

internal class WebHandler : HttpHandler {
    private final val charset: Charset = Charsets.UTF_8

    override fun handle(t: HttpExchange) {
        try {
            println("Received request: ${t.requestMethod} ${t.requestURI.path} ; User: ${t.getUser()}")
            val cleanRequestPath = t.requestURI.path.replace(Regex("/$"), "")
            when {
                cleanRequestPath == "/tmsacoin" -> handleIndex(t)
                cleanRequestPath == "/tmsacoin/new_account" -> handleNewAccountForm(t)
                cleanRequestPath == "/tmsacoin/mint" -> handleMintForm(t)
                cleanRequestPath == "/tmsacoin/transfer" -> handleTransferForm(t)
                cleanRequestPath == "/tmsacoin/login" -> handleLoginForm(t)
                cleanRequestPath == "/tmsacoin/balance" -> handleBalanceRequest(t, t.getUser())
                cleanRequestPath.matches(Regex("/tmsacoin/balance/[a-zA-Z]{1,32}")) -> handleBalanceRequest(t, cleanRequestPath.split("/").last())
                cleanRequestPath == "/tmsacoin/internal/new_account" -> handleNewAccountRequest(t)
                cleanRequestPath == "/tmsacoin/internal/mint" -> handleMintRequest(t)
                cleanRequestPath == "/tmsacoin/internal/transfer" -> handleTransferRequest(t)
                cleanRequestPath == "/tmsacoin/internal/login" -> handleLoginRequest(t)
                cleanRequestPath == "/tmsacoin/internal/logout" -> handleLogoutRequest(t)
                else -> handleNotFound(t)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            t.sendError(text = "Internal error")
        }
    }

    private fun handleIndex(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "index.html")
    }

    private fun handleNewAccountForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "new_account.html")
    }

    private fun handleMintForm(httpExchange: HttpExchange) {
        if (DBHandler.isAdmin(httpExchange.getUser())) {
            httpExchange.sendSuccess(fileName = "mint.html")
        } else {
            httpExchange.sendUnauthorized(text = "You are not authorized to mint TMSACoin.")
        }
    }

    private fun handleTransferForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "transfer.html")
    }

    private fun handleLoginForm(httpExchange: HttpExchange) {
        httpExchange.sendSuccess(fileName = "login.html")
    }

    private fun handleBalanceRequest(httpExchange: HttpExchange, user: String?) {
        httpExchange.sendSuccess(text =
        """
            <pre>Balance of $user: ${DBHandler.getUserBalance(user)}</pre>
        """.trimIndent())
    }

    private fun handleNewAccountRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()
        httpExchange.requireParameter("username")
        httpExchange.requireParameter("password")
        val createResult = DBHandler.createUser(httpExchange.parameter("username"), httpExchange.parameter("password"))
        if (DBHandler.isAdmin(httpExchange.getUser()) &&
                httpExchange.hasParameter("admin") &&
                httpExchange.parameter("admin") == "on") {

            DBHandler.makeAdmin(httpExchange.parameter("username"))
        }
        if (createResult.first) {
            httpExchange.sendSuccess(text = "Done.")
        } else {
            httpExchange.sendError(text = createResult.second)
        }
    }

    private fun handleMintRequest(httpExchange: HttpExchange) {
        if (DBHandler.isAdmin(httpExchange.getUser())) {
            httpExchange.requirePost()
            httpExchange.requireParameter("recipient")
            httpExchange.requireParameter("amount")
            val storeResult = DBHandler.storeTransaction(null, httpExchange.parameter("recipient"), httpExchange.parameter("amount").toFloat())
            if (storeResult.first) {
                httpExchange.sendSuccess(text = "Done.")
            } else {
                httpExchange.sendError(text = storeResult.second)
            }
        } else {
            httpExchange.sendUnauthorized(text = "You are not authorized to mint TMSACoin.")
        }
    }

    private fun handleTransferRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()
        httpExchange.requireParameter("to")
        httpExchange.requireParameter("amount")
        val storeResult = DBHandler.storeTransaction(httpExchange.getUser(), httpExchange.parameter("to"), httpExchange.parameter("amount").toFloat())
        if (storeResult.first) {
            httpExchange.sendSuccess(text = "Done.")
        } else {
            httpExchange.sendError(text = storeResult.second)
        }
    }

    private fun handleLoginRequest(httpExchange: HttpExchange) {
        httpExchange.requirePost()
        httpExchange.requireParameter("username")
        httpExchange.requireParameter("password")

//        handleLogoutRequest(httpExchange) // Delete pre-existing token

        val username = httpExchange.parameter("username")
        val password = httpExchange.parameter("password")
        if (DBHandler.verifyPassword(username, password)) {
            httpExchange.writeCookie(
                    "tmsacoin-session",
                    DBHandler.createSession(
                            username,
                            Timestamp.from(Instant.now().plus(7, ChronoUnit.DAYS))
                    ),
                    60 * 60 * 24 * 7
            )
            httpExchange.sendSuccess(text = "Login successful.")
        } else {
            httpExchange.sendUnauthorized(text = "Invalid login.")
        }
    }

    private fun handleLogoutRequest(httpExchange: HttpExchange) {
        DBHandler.deleteSession(httpExchange.getToken())
        httpExchange.sendSuccess(text = "Done.")
    }

    private fun handleNotFound(httpExchange: HttpExchange) {
        httpExchange.sendNotFound(text = "URL not found.")
    }
}