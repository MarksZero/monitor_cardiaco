package com.example.monitorcardiaco

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.Locale

data class Usuario(val numero: Long, val nombre: String, val rol: String, val activo: Boolean)

val roles = listOf("administrador", "monitoreado", "familiar")
fun rolDelEquipo(rol: String) = when (rol) {
    "administrador" -> "admin"
    "monitoreado" -> "patient"
    "familiar" -> "family"
    else -> error("Rol desconocido")
}

class BaseDatos(contexto: Context, archivo: String = "cuentas.db") : SQLiteOpenHelper(contexto, archivo, null, 1,
    DatabaseErrorHandler { throw SQLiteDatabaseCorruptException("Base dañada. No se borraron las cuentas; requiere recuperación.") }) {
    override fun onCreate(base: SQLiteDatabase) {
        base.execSQL("""CREATE TABLE usuarios (
            numero INTEGER PRIMARY KEY AUTOINCREMENT,
            nombre TEXT NOT NULL UNIQUE,
            rol TEXT NOT NULL CHECK(rol IN ('administrador','monitoreado','familiar')),
            activo INTEGER NOT NULL CHECK(activo IN (0,1)),
            sal TEXT NOT NULL, resumen TEXT NOT NULL, vueltas INTEGER NOT NULL,
            equipo TEXT)""")
        base.execSQL("CREATE TABLE intentos (numero INTEGER PRIMARY KEY CHECK(numero=1), fallos INTEGER NOT NULL, bloqueo INTEGER NOT NULL)")
        base.execSQL("INSERT INTO intentos VALUES (1,0,0)")
    }

    override fun onUpgrade(base: SQLiteDatabase, anterior: Int, nueva: Int) {
        error("Falta definir la migración de la base de datos. No se borraron las cuentas.")
    }

    @Synchronized
    fun hayCuentas(): Boolean = readableDatabase.rawQuery("SELECT 1 FROM usuarios LIMIT 1", null).use { it.moveToFirst() }

    private fun nombreValido(nombre: String): String {
        val limpio = nombre.trim().lowercase(Locale.ROOT)
        require(limpio.matches(Regex("[a-z0-9_-]{1,24}"))) { "Nombre: 1 a 24 letras, números, guion o guion bajo" }
        return limpio
    }

    private fun leerUsuario(fila: Cursor) = Usuario(fila.getLong(0), fila.getString(1), fila.getString(2), fila.getInt(3) == 1)

    private fun buscar(numero: Long): Usuario? = readableDatabase.rawQuery(
        "SELECT numero,nombre,rol,activo FROM usuarios WHERE numero=?", arrayOf(numero.toString())
    ).use { if (it.moveToFirst()) leerUsuario(it) else null }

    fun comprobarUsuario(numero: Long): Usuario {
        val usuario = buscar(numero)
        check(usuario != null && usuario.activo) { "La cuenta ya no está habilitada" }
        return usuario
    }

    private fun comprobarAdmin(numero: Long) {
        check(comprobarUsuario(numero).rol == "administrador") { "Solo el administrador puede realizar esta operación" }
    }

    // Una transacción evita crear dos administradores iniciales por doble pulsación.
    @Synchronized
    fun crearAdministrador(nombre: String, clave: String) {
        val limpio = nombreValido(nombre)
        val resumen = Seguridad.crearResumen(clave)
        val base = writableDatabase
        base.beginTransaction()
        try {
            check(!hayCuentas()) { "Ya existe una instalación configurada. Inicia sesión." }
            base.insertOrThrow("usuarios", null, valores(limpio, "administrador", true, resumen))
            base.setTransactionSuccessful()
        } finally { base.endTransaction() }
    }

    private fun valores(nombre: String, rol: String, activo: Boolean, clave: ResumenClave?) = ContentValues().apply {
        put("nombre", nombre); put("rol", rol); put("activo", if (activo) 1 else 0)
        if (clave != null) {
            put("sal", clave.sal); put("resumen", clave.resumen); put("vueltas", clave.vueltas)
        }
    }

    @Synchronized
    fun entrar(nombre: String, clave: String): Usuario {
        val base = writableDatabase
        base.beginTransaction()
        try {
            val ahora = System.currentTimeMillis()
            var fallos = 0
            base.rawQuery("SELECT fallos,bloqueo FROM intentos WHERE numero=1", null).use {
                check(it.moveToFirst()) { "No se pudo leer el control de acceso" }
                fallos = it.getInt(0)
                check(ahora >= it.getLong(1)) { "Demasiados intentos. Espera un minuto antes de volver a entrar." }
            }
            var usuario: Usuario? = null
            var correcto = false
            base.rawQuery("SELECT numero,nombre,rol,activo,sal,resumen,vueltas FROM usuarios WHERE nombre=?",
                arrayOf(nombre.trim().lowercase(Locale.ROOT))).use {
                if (it.moveToFirst()) {
                    val actual = leerUsuario(it)
                    usuario = actual
                    correcto = Seguridad.comprobarClave(clave, it.getString(4), it.getString(5), it.getInt(6)) && actual.activo
                } else {
                    // Mismo trabajo de derivación aunque no exista el nombre.
                    Seguridad.comprobarClave(clave, "AAAAAAAAAAAAAAAAAAAAAA==",
                        "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=", 600000)
                }
            }
            if (!correcto) {
                val cantidad = fallos + 1
                base.update("intentos", ContentValues().apply {
                    put("fallos", if (cantidad >= 5) 0 else cantidad)
                    put("bloqueo", if (cantidad >= 5) ahora + 60000 else 0)
                }, "numero=1", null)
                // Confirmar el contador incluso cuando el inicio de sesión falla.
                base.setTransactionSuccessful()
                error("Usuario o contraseña incorrectos, o cuenta deshabilitada")
            }
            base.execSQL("UPDATE intentos SET fallos=0,bloqueo=0 WHERE numero=1")
            base.setTransactionSuccessful()
            return checkNotNull(usuario)
        } finally {
            base.endTransaction()
        }
    }

    @Synchronized
    fun listar(administrador: Long): List<Usuario> {
        comprobarAdmin(administrador)
        val lista = mutableListOf<Usuario>()
        readableDatabase.rawQuery("SELECT numero,nombre,rol,activo FROM usuarios ORDER BY nombre", null).use {
            while (it.moveToNext()) lista.add(leerUsuario(it))
        }
        return lista
    }

    // Crear o editar por nombre. Contraseña vacía conserva la anterior al editar.
    @Synchronized
    fun guardar(administrador: Long, nombre: String, clave: String, rol: String, activo: Boolean) {
        comprobarAdmin(administrador)
        val limpio = nombreValido(nombre)
        require(rol in roles) { "Rol inválido" }
        var anterior: Usuario? = null
        readableDatabase.rawQuery("SELECT numero,nombre,rol,activo FROM usuarios WHERE nombre=?", arrayOf(limpio)).use {
            if (it.moveToFirst()) anterior = leerUsuario(it)
        }
        require(anterior?.numero != administrador) { "No puedes editar tu propia cuenta desde este formulario" }
        val resumen = if (clave.isNotEmpty()) Seguridad.crearResumen(clave) else null
        require(anterior != null || resumen != null) { "Escribe una contraseña para la cuenta nueva" }
        val datos = valores(limpio, rol, activo, resumen)
        val previa = anterior
        if (previa == null) writableDatabase.insertOrThrow("usuarios", null, datos)
        else {
            // Al cambiar permisos se retira el vínculo previo con el ESP.
            if (previa.rol != rol || !activo) datos.putNull("equipo")
            writableDatabase.update("usuarios", datos, "numero=?", arrayOf(previa.numero.toString()))
        }
    }

    @Synchronized
    fun eliminar(administrador: Long, numero: Long) {
        comprobarAdmin(administrador)
        require(administrador != numero) { "No puedes eliminar tu propia cuenta" }
        writableDatabase.delete("usuarios", "numero=?", arrayOf(numero.toString()))
    }

    @Synchronized
    fun guardarEquipo(administrador: Long, nombre: String, ip: String, usuario: String, clave: String) {
        comprobarAdmin(administrador)
        require(ipLocalValida(ip) && usuario.matches(REGEX_NOMBRE) && clave.matches(REGEX_CLAVE)) { "Revisa IP, usuario ESP y token de 64 caracteres" }
        val limpio = nombreValido(nombre)
        val numero = readableDatabase.rawQuery("SELECT numero FROM usuarios WHERE nombre=? AND activo=1", arrayOf(limpio)).use {
            check(it.moveToFirst()) { "No existe esa cuenta local activa" }
            it.getLong(0)
        }
        val texto = JSONObject().put("ip", ip).put("usuario", usuario).put("clave", clave).toString()
        val datos = ContentValues().apply { put("equipo", Seguridad.cifrar(texto, numero)) }
        writableDatabase.update("usuarios", datos, "numero=?", arrayOf(numero.toString()))
    }

    @Synchronized
    fun leerEquipo(numero: Long): Sesion {
        val usuario = comprobarUsuario(numero)
        val cifrado = readableDatabase.rawQuery("SELECT equipo FROM usuarios WHERE numero=?", arrayOf(numero.toString())).use {
            check(it.moveToFirst() && !it.isNull(0)) { "Esta cuenta no tiene conexión ESP configurada" }
            it.getString(0)
        }
        val datos = JSONObject(Seguridad.descifrar(cifrado, numero))
        return Sesion(datos.getString("ip"), datos.getString("usuario"), datos.getString("clave"), rolDelEquipo(usuario.rol))
    }
}
