
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

fun main(args: Array<String>) {
    val server = HttpServer.create(InetSocketAddress(8000), 999999999)
    server.createContext("/", WebHandler())
    server.start()
}