import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

fun main() {
    val encoder = BCryptPasswordEncoder()
    val hash = "\$2a\$10\$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy"
    println("Password 'admin' matches hash: ${encoder.matches("admin", hash)}")
    println("New hash for 'admin': ${encoder.encode("admin")}")
}