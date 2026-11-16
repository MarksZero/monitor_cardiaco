package com.example.monitorcardiaco

// Prueba JVM: no abre Android Keystore ni requiere teléfono.
fun main() {
    val clave = "Clave de prueba 123"
    val primera = Seguridad.crearResumen(clave)
    val segunda = Seguridad.crearResumen(clave)
    check(primera.sal != segunda.sal)
    check(primera.resumen != segunda.resumen)
    check(Seguridad.comprobarClave(clave, primera.sal, primera.resumen, primera.vueltas))
    check(!Seguridad.comprobarClave("Contraseña equivocada", primera.sal, primera.resumen, primera.vueltas))
    // Resultado independiente calculado con PBKDF2-SHA256 de .NET, sal de 16 ceros.
    check(Seguridad.comprobarClave(clave, "AAAAAAAAAAAAAAAAAAAAAA==",
        "7VwuKxmoKZ3ksRFvGHYac0htjjEdXjwjKP/rAuT457o=", 600000))
    check(runCatching { Seguridad.crearResumen("corta") }.isFailure)
    check(runCatching { Seguridad.crearResumen("x".repeat(129)) }.isFailure)
    check(runCatching { Seguridad.comprobarClave(clave, primera.sal, primera.resumen, 1) }.isFailure)
    println("OK: contraseña correcta e incorrecta, sal aleatoria, vector independiente, longitud y parámetros")
}
