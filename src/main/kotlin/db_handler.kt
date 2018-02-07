import java.security.MessageDigest
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
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
    private lateinit var userPasswordInfo: PreparedStatement

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
    }

    private fun ensureTablesExist() {
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS transactions(id BIGINT IDENTITY PRIMARY KEY NOT NULL, user_from VARCHAR(32), user_to VARCHAR(32) NOT NULL, amount INT)")
        connection().createStatement().execute("CREATE TABLE IF NOT EXISTS users(username VARCHAR(32) PRIMARY KEY, pw_hash VARCHAR(128) NOT NULL, salt VARCHAR(20) NOT NULL, balance_cache INT DEFAULT 0 NOT NULL)")
    }

    private fun initPreparedStatements() {
        getUserCacheStatement = connection().prepareStatement("SELECT balance_cache FROM users WHERE username = ?")
        setUserCacheStatement = connection().prepareStatement("UPDATE users SET balance_cache = ? WHERE username = ?")
        addTransactionStatement = connection().prepareStatement("INSERT INTO transactions (user_from, user_to, amount) VALUES (?, ?, ?)")
        getUserByUsername = connection().prepareStatement("SELECT * FROM users WHERE username = ?")
        createUser = connection().prepareStatement("INSERT INTO users (username, pw_hash, salt) VALUES (?,?,?)")
        userPasswordInfo = connection().prepareStatement("SELECT pw_hash, salt FROM users WHERE username = ?")
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

    fun storeTransaction(fromUser: String?, toUser: String, amount: Int) {
        connection()
        if (!userExists(fromUser, true) || !userExists(toUser)) throw IllegalArgumentException("Users must exist")
        val fromBalance = if (fromUser != null) getUserBalance(fromUser) else amount
        if (fromBalance < amount) throw IllegalArgumentException("Insufficient balance")
        addTransactionStatement.setString(1, fromUser)
        addTransactionStatement.setString(2, toUser)
        addTransactionStatement.setInt(3, amount)
        addTransactionStatement.execute()
        addTransactionStatement.clearParameters()
        if (fromUser != null) setUserBalanceCache(fromUser, fromBalance - amount)
        setUserBalanceCache(toUser, getUserBalance(toUser) + amount)
    }

    fun getUserBalance(user: String?): Int {
        connection()
        if (user == null) return 0
        if (!userExists(user)) throw IllegalArgumentException("User must exist")
        getUserCacheStatement.setString(1, user)
        val resultSet: ResultSet = getUserCacheStatement.executeQuery()
        getUserCacheStatement.clearParameters()
        return if (resultSet.next()) resultSet.getInt(1) else 0
    }

    private fun setUserBalanceCache(user: String?, amount: Int) {
        connection()
        if (!userExists(user, false)) throw IllegalArgumentException("User must exist")
        setUserCacheStatement.setInt(1, amount)
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
        userPasswordInfo.setString(1, user)
        val passwordInfo = userPasswordInfo.executeQuery()
        userPasswordInfo.clearParameters()
        passwordInfo.next()
        val salt = passwordInfo.getString("salt")
        val checkHash = passwordInfo.getString("pw_hash")
        val newHash = hashPassword(password, salt)

        return newHash == checkHash
    }
}