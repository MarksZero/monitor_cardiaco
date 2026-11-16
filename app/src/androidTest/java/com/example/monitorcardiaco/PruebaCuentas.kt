package com.example.monitorcardiaco

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

// Pruebas Android. Usan un archivo propio y no borran cuentas.db.
class PruebaCuentas {
    private lateinit var contexto: Context
    private lateinit var base: BaseDatos
    private lateinit var archivo: String
    private val clave = "Clave de prueba 123"

    @Before fun preparar() {
        contexto = InstrumentationRegistry.getInstrumentation().targetContext
        archivo = "prueba-cuentas-${UUID.randomUUID()}.db"
        base = BaseDatos(contexto, archivo)
    }

    @After fun terminar() {
        base.close()
        contexto.deleteDatabase(archivo)
    }

    @Test fun accesoSinEspYAdministradorUnico() {
        assertFalse(base.hayCuentas())
        base.crearAdministrador("Dueno", clave)
        val administrador = base.entrar("DUENO", clave)
        assertEquals("administrador", administrador.rol)
        assertTrue(runCatching { base.crearAdministrador("otro", clave) }.isFailure)
    }

    @Test fun permisosYCambiosDeRol() {
        base.crearAdministrador("admin", clave)
        val administrador = base.entrar("admin", clave)
        base.guardar(administrador.numero, "persona", clave, "monitoreado", true)
        val persona = base.entrar("persona", clave)
        assertTrue(runCatching { base.guardar(persona.numero, "intruso", clave, "administrador", true) }.isFailure)
        assertTrue(runCatching { base.eliminar(administrador.numero, administrador.numero) }.isFailure)
        base.guardar(administrador.numero, "persona", "", "familiar", true)
        assertEquals("familiar", base.entrar("persona", clave).rol)
        base.guardar(administrador.numero, "persona", "", "familiar", false)
        assertTrue(runCatching { base.entrar("persona", clave) }.isFailure)
        base.eliminar(administrador.numero, persona.numero)
        assertEquals(1, base.listar(administrador.numero).size)
    }

    @Test fun bloqueoSobreviveAReabrirBase() {
        base.crearAdministrador("admin", clave)
        repeat(5) { assertTrue(runCatching { base.entrar("admin", "clave incorrecta") }.isFailure) }
        base.close()
        base = BaseDatos(contexto, archivo)
        assertTrue(runCatching { base.entrar("admin", clave) }.isFailure)
    }

    @Test fun cifradoDeEquipoNoSePuedeCambiarDeCuenta() {
        val texto = Seguridad.cifrar("dato de prueba", 1)
        assertEquals("dato de prueba", Seguridad.descifrar(texto, 1))
        assertTrue(runCatching { Seguridad.descifrar(texto, 2) }.isFailure)
        val otro = Seguridad.cifrar("otro dato", 1)
        val partes = otro.split(':')
        val modificado = texto.split(':')[0] + ":" + partes[1]
        assertTrue(runCatching { Seguridad.descifrar(modificado, 1) }.isFailure)
    }
}
