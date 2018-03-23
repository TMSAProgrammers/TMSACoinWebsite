
import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.*
import java.time.Instant
import kotlin.experimental.and
import kotlin.math.roundToLong


internal object DBHandler {
    private var initDone = false
    private lateinit var dbConnection: Connection
    private lateinit var getUserCacheStatement: PreparedStatement
    private lateinit var setUserCacheStatement: PreparedStatement
    private lateinit var addTransactionStatement: PreparedStatement
    private lateinit var getUserByUsername: PreparedStatement
    private lateinit var createUser: PreparedStatement
    private lateinit var getUserPassword: PreparedStatement
    private lateinit var getUserSalt: PreparedStatement
    private lateinit var storeSession: PreparedStatement
    private lateinit var getSessionInfo: PreparedStatement
    private lateinit var invalidateSessionID: PreparedStatement
    private lateinit var deleteUserSessions: PreparedStatement
    private lateinit var userIsAdmin: PreparedStatement
    private lateinit var makeAdmin: PreparedStatement

    private fun connection(): Connection {
        if (!initDone) init()
        return dbConnection
    }

     fun init() {
        if (initDone) return
        Class.forName("org.hsqldb.jdbc.JDBCDriver" )
        dbConnection = DriverManager.getConnection("jdbc:hsqldb:file:coindb;shutdown=true", "SA", "")
        initDone = true
        ensureTablesExist()
        initPreparedStatements()

         Runtime.getRuntime().addShutdownHook(object : Thread() {
             override fun run() {
                 DBHandler.shutdown()
             }
         })
    }

    private fun ensureTablesExist() {
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS transactions(id BIGINT IDENTITY PRIMARY KEY NOT NULL, sender VARCHAR(32), recipient VARCHAR(32) NOT NULL, amount FLOAT)")
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS users(username VARCHAR(32) PRIMARY KEY, is_admin BOOLEAN DEFAULT FALSE, pw_hash VARCHAR(128) NOT NULL, salt VARCHAR(20) NOT NULL, balance_cache FLOAT DEFAULT 0 NOT NULL)")
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS sessions(token VARCHAR(128) PRIMARY KEY, username VARCHAR(32) NOT NULL, expiry TIMESTAMP NOT NULL)")
    }

    private fun initPreparedStatements() {
        getUserCacheStatement = connection().prepareStatement("SELECT balance_cache FROM users WHERE username = ?")
        setUserCacheStatement = connection().prepareStatement("UPDATE users SET balance_cache = ? WHERE username = ?")
        addTransactionStatement = connection().prepareStatement("INSERT INTO transactions (sender, recipient, amount) VALUES (?, ?, ?)")
        getUserByUsername = connection().prepareStatement("SELECT * FROM users WHERE username = ?")
        createUser = connection().prepareStatement("INSERT INTO users (username, pw_hash, salt) VALUES (?,?,?)")
        getUserPassword = connection().prepareStatement("SELECT pw_hash FROM users WHERE username = ?")
        getUserSalt = connection().prepareStatement("SELECT salt FROM users WHERE username = ?")
        storeSession = connection().prepareStatement("INSERT INTO sessions (token, username, expiry) VALUES (?,?,?)")
        getSessionInfo = connection().prepareStatement("SELECT username, expiry FROM sessions WHERE token = ?")
        invalidateSessionID = connection().prepareStatement("DELETE FROM sessions WHERE token = ?")
        deleteUserSessions = connection().prepareStatement("DELETE FROM sessions WHERE username = ?")
        userIsAdmin = connection().prepareStatement("SELECT is_admin FROM users WHERE username = ?")
        makeAdmin = connection().prepareStatement("UPDATE users SET is_admin = TRUE WHERE username = ?")
    }

    fun createUser(username: String, password: String): Pair<Boolean, String?> {
        connection()
        if (userExists(username)) return false to "User already exists"
        val random = SecureRandom()
        var salt: String = (random.nextInt() * random.nextLong() - (random.nextDouble() * random.nextInt()).roundToLong()).toString(16)
        var hash = hashPassword(password, salt)
        createUser.setString(1, username)
        createUser.setString(2, hash)
        createUser.setString(3, salt)
        createUser.execute()
        createUser.clearParameters()
        return true to "User successfully created"
    }

    fun storeTransaction(fromUser: String?, toUser: String, amount: Float): Pair<Boolean, String?> {
        connection()
        if (amount < 0) return false to "Amount must be positive"
        if (!userExists(fromUser, true) || !userExists(toUser)) return false to "Users must exist"
        val fromBalance = if (fromUser != null) getUserBalance(fromUser) else amount
        if (fromBalance < amount) return false to "Insufficient balance"
        addTransactionStatement.setString(1, fromUser)
        addTransactionStatement.setString(2, toUser)
        addTransactionStatement.setFloat(3, amount)
        addTransactionStatement.execute()
        addTransactionStatement.clearParameters()
        if (fromUser != null) setUserBalanceCache(fromUser, fromBalance - amount)
        setUserBalanceCache(toUser, getUserBalance(toUser) + amount)
        return true to null
    }

    fun getUserBalance(user: String?): Float {
        connection()
        if (!userExists(user)) return 0f
        getUserCacheStatement.setString(1, user)
        val resultSet: ResultSet = getUserCacheStatement.executeQuery()
        getUserCacheStatement.clearParameters()
        return if (resultSet.next()) resultSet.getFloat(1) else 0f
    }

    private fun setUserBalanceCache(user: String?, amount: Float) {
        connection()
        if (!userExists(user, false)) throw IllegalArgumentException("User must exist")
        setUserCacheStatement.setFloat(1, amount)
        setUserCacheStatement.setString(2, user)
        setUserCacheStatement.execute()
        setUserCacheStatement.clearParameters()
    }

    fun userExists(user: String?, nullValue: Boolean = false): Boolean {
        connection()
        if (user == null) return nullValue
        getUserByUsername.setString(1, user)
        val ret: Boolean = getUserByUsername.executeQuery().next()
        getUserByUsername.clearParameters()
        return ret
    }

    fun shutdown() {
        connection().commit()
        connection().close()
        println("DB shutdown")
    }

    fun hashPassword(passwordToHash: String, salt: String): String {
        val generatedPassword: String
        val md = MessageDigest.getInstance("SHA-512")
        md.update(salt.toByteArray(Charsets.UTF_8))
        val bytes = md.digest(passwordToHash.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in bytes.indices) {
            sb.append(Integer.toString((bytes[i] and 0xff.toByte()) + 0x100, 16).substring(1))
        }
        generatedPassword = sb.toString()

        return generatedPassword
    }

    fun verifyPassword(user: String, password: String): Boolean {
        connection()
        if (!userExists(user)) return false
        val salt = getSalt(user)
        val checkHash = getPassword(user)
        val newHash = hashPassword(password, salt)

        return newHash == checkHash
    }

    fun getPassword(user: String): String {
        connection()
        if (!userExists(user)) throw IllegalArgumentException("User must exist")
        getUserPassword.setString(1, user)
        val resultSet = getUserPassword.executeQuery()
        resultSet.next()
        val password = resultSet.getString(1)
        getUserPassword.clearParameters()
        return password
    }

    fun getSalt(user: String): String {
        connection()
        if (!userExists(user)) throw IllegalArgumentException("User must exist")
        getUserSalt.setString(1, user)
        val resultSet = getUserSalt.executeQuery()
        resultSet.next()
        val salt = resultSet.getString(1)
        getUserSalt.clearParameters()
        return salt
    }

    fun createSession(user: String, expiry: Timestamp): String {
        connection()
        clearUserSessions(user)
        val token = hashPassword("${getPassword(user)} / $expiry", getSalt(user) + user)
        storeSession.setString(1, token)
        storeSession.setString(2, user)
        storeSession.setTimestamp(3, expiry)
        storeSession.execute()
        storeSession.clearParameters()
        return token
    }

    fun clearUserSessions(user: String) {
        connection()
        deleteUserSessions.setString(1, user)
        deleteUserSessions.execute()
        deleteUserSessions.clearParameters()
    }

    fun getSessionUser(token: String): String? { // (Username, Expiry)
        connection()
        getSessionInfo.setString(1, token)
        val result = getSessionInfo.executeQuery()
        getSessionInfo.clearParameters()
        if (result.next()) {
            val username = result.getString(1)
            val expiry = result.getTimestamp(2)
            if (expiry.toInstant().isBefore(Instant.now())) {
                deleteSession(token)
                return null
            }
            return username
        } else return null
    }

    fun deleteSession(token: String?) {
        connection()
        invalidateSessionID.setString(1, token)
        invalidateSessionID.execute()
        invalidateSessionID.clearParameters()
    }

    fun isAdmin(username: String?): Boolean {
        connection()
        userIsAdmin.setString(1, username)
        val result = userIsAdmin.executeQuery()
        userIsAdmin.clearParameters()
        return if (result.next()) result.getBoolean(1) else false
    }

    fun makeAdmin(username: String) {
        connection()
        makeAdmin.setString(1, username)
        makeAdmin.execute()
        makeAdmin.clearParameters()
    }
}