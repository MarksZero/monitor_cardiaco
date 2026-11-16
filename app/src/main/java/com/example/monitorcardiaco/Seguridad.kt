package com.example.monitorcardiaco

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec

data class ResumenClave(val sal: String, val resumen: String, val vueltas: Int)

object Seguridad {
    private const val vueltas = 600000
    private const val nombreLlave = "monitor_clave_equipo"

    fun revisarClave(clave: String) {
        require(clave.length in 15..128 && clave.isNotBlank()) {
            "Usa una contraseña de 15 a 128 caracteres. Puede ser una frase."
        }
    }

    fun crearResumen(clave: String): ResumenClave {
        revisarClave(clave)
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return ResumenClave(codificar(sal), codificar(calcular(clave, sal, vueltas)), vueltas)
    }

    fun comprobarClave(clave: String, sal: String, resumen: String, veces: Int): Boolean {
        require(veces in 600000..1200000) { "La cuenta tiene parámetros de contraseña inválidos" }
        if (clave.length !in 1..128) return false
        val salBytes = Base64.getDecoder().decode(sal)
        val guardado = Base64.getDecoder().decode(resumen)
        require(salBytes.size == 16 && guardado.size == 32) { "La cuenta tiene datos inválidos" }
        return MessageDigest.isEqual(calcular(clave, salBytes, veces), guardado)
    }

    private fun calcular(clave: String, sal: ByteArray, veces: Int): ByteArray {
        val letras = clave.toCharArray()
        val datos = PBEKeySpec(letras, sal, veces, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(datos).encoded
        } finally {
            datos.clearPassword()
            letras.fill('\u0000')
        }
    }

    private fun codificar(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    // Keystore protege la llave; el texto cifrado se guarda en la base de datos.
    @Synchronized
    private fun obtenerLlave(crear: Boolean): SecretKey {
        val almacen = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existente = almacen.getKey(nombreLlave, null)
        if (existente is SecretKey) return existente
        check(crear) { "No se encuentra la llave del equipo. El administrador debe configurar de nuevo la conexión." }
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generador.init(KeyGenParameterSpec.Builder(nombreLlave,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256).build())
        return generador.generateKey()
    }

    fun cifrar(texto: String, cuenta: Long): String {
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.ENCRYPT_MODE, obtenerLlave(true))
        cifrador.updateAAD("cuenta:$cuenta".toByteArray(Charsets.UTF_8))
        return codificar(cifrador.iv) + ":" + codificar(cifrador.doFinal(texto.toByteArray(Charsets.UTF_8)))
    }

    fun descifrar(texto: String, cuenta: Long): String {
        val partes = texto.split(':')
        require(partes.size == 2) { "Configuración cifrada inválida" }
        val iv = Base64.getDecoder().decode(partes[0])
        require(iv.size == 12) { "Configuración cifrada inválida" }
        val cifrador = Cipher.getInstance("AES/GCM/NoPadding")
        cifrador.init(Cipher.DECRYPT_MODE, obtenerLlave(false), GCMParameterSpec(128, iv))
        cifrador.updateAAD("cuenta:$cuenta".toByteArray(Charsets.UTF_8))
        return String(cifrador.doFinal(Base64.getDecoder().decode(partes[1])), Charsets.UTF_8)
    }
}
