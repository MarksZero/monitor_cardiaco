package com.example.monitorcardiaco

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import java.io.File

data class EstadoPantalla(
    val preparado: Boolean = false,
    val hayCuentas: Boolean = false,
    val usuario: Usuario? = null,
    val usuarios: List<Usuario> = emptyList(),
    val datos: JSONObject? = null,
    val conectado: Boolean = false,
    val ocupado: Boolean = false,
    val mensaje: String = "",
    val errorConexion: String = ""
)

class Vista(app: Application) : AndroidViewModel(app) {
    private val base = BaseDatos(app)
    private val interno = MutableStateFlow(EstadoPantalla())
    val estado = interno.asStateFlow()
    private var operacion: Job? = null
    private var consulta: Job? = null
    private var visible = false
    private var consultar = false
    private var ultimoRegistro = 0L

    init {
        ejecutar {
            val existen = withContext(Dispatchers.IO) { base.hayCuentas() }
            interno.update { it.copy(preparado = true, hayCuentas = existen) }
            ""
        }
    }

    fun avisar(texto: String) { interno.update { it.copy(mensaje = texto) } }

    fun crearAdministrador(nombre: String, clave: String, repetida: String) {
        ejecutar {
            avisar("Creando administrador…")
            require(clave == repetida) { "Las contraseñas no coinciden" }
            withContext(Dispatchers.IO) { base.crearAdministrador(nombre, clave) }
            interno.update { it.copy(hayCuentas = true) }
            "Administrador creado. Ya puedes iniciar sesión sin conectar el ESP."
        }
    }

    fun iniciarSesion(nombre: String, clave: String) {
        ejecutar {
            avisar("Validando usuario y contraseña…")
            val usuario = withContext(Dispatchers.IO) { base.entrar(nombre, clave) }
            val lista = withContext(Dispatchers.IO) {
                if (usuario.rol == "administrador") base.listar(usuario.numero) else emptyList()
            }
            interno.update { it.copy(usuario = usuario, usuarios = lista, datos = null, errorConexion = "") }
            "Sesión local iniciada. La conexión al ESP es independiente."
        }
    }

    fun guardarUsuario(nombre: String, clave: String, rol: String, activo: Boolean) {
        val administrador = interno.value.usuario ?: return
        ejecutar {
            val lista = withContext(Dispatchers.IO) {
                base.guardar(administrador.numero, nombre, clave, rol, activo)
                base.listar(administrador.numero)
            }
            interno.update { it.copy(usuarios = lista) }
            "Cuenta local guardada"
        }
    }

    fun eliminarUsuario(numero: Long) {
        val administrador = interno.value.usuario ?: return
        ejecutar {
            val lista = withContext(Dispatchers.IO) {
                base.eliminar(administrador.numero, numero)
                base.listar(administrador.numero)
            }
            interno.update { it.copy(usuarios = lista) }
            "Cuenta local eliminada"
        }
    }

    fun guardarEquipo(nombre: String, ip: String, usuario: String, clave: String) {
        val administrador = interno.value.usuario ?: return
        ejecutar {
            withContext(Dispatchers.IO) { base.guardarEquipo(administrador.numero, nombre, ip, usuario, clave) }
            "Conexión guardada en este teléfono. No se creó ninguna cuenta en el ESP."
        }
    }

    fun conectar() {
        if (interno.value.usuario == null) return
        consultar = true
        empezarConsulta()
    }

    fun empezarConsulta() {
        visible = true
        if (!consultar || consulta?.isActive == true || interno.value.usuario == null) return
        consulta = viewModelScope.launch {
            while (isActive && visible) {
                val usuario = interno.value.usuario ?: break
                try {
                    val datos = withContext(Dispatchers.IO) {
                        val actual = base.comprobarUsuario(usuario.numero)
                        check(actual.rol == usuario.rol) { "El rol cambió; vuelve a iniciar sesión" }
                        val respuesta = Conexion(base.leerEquipo(usuario.numero)).peticion("/status")
                        check(respuesta.getString("role") == rolDelEquipo(actual.rol)) {
                            "El rol del ESP no coincide con la cuenta local. Revisa la configuración."
                        }
                        // No interpretar campos ausentes como mediciones válidas.
                        respuesta.getBoolean("signal")
                        respuesta.getInt("bpm")
                        respuesta.getLong("uptime")
                        respuesta.optJSONObject("alert")?.let { alerta ->
                            alerta.getString("kind")
                            alerta.getLong("uptime")
                            if (alerta.has("lat")) {
                                val latitud = alerta.getDouble("lat")
                                val longitud = alerta.getDouble("lon")
                                val precision = alerta.getDouble("accuracy")
                                check(latitud.isFinite() && latitud in -90.0..90.0 &&
                                        longitud.isFinite() && longitud in -180.0..180.0 && precision.isFinite() && precision >= 0) {
                                    "El ESP devolvió una ubicación inválida"
                                }
                            }
                        }
                        respuesta
                    }
                    interno.update { it.copy(datos = datos, conectado = true, errorConexion = "") }
                    if (usuario.rol == "monitoreado" && datos.getBoolean("signal")) {
                        try {
                            withContext(Dispatchers.IO) { guardarRegistro(usuario.numero, datos.getInt("bpm")) }
                        } catch (e: CancellationException) { throw e
                        } catch (_: Exception) { avisar("Hay medición, pero no se pudo guardar el registro cifrado") }
                    }
                } catch (e: CancellationException) { throw e
                } catch (e: Exception) {
                    interno.update { it.copy(conectado = false, datos = null, errorConexion = e.message ?: "No se pudo consultar el ESP") }
                }
                delay(2000)
            }
        }
    }

    private fun guardarRegistro(numero: Long, bpm: Int) {
        val ahora = System.currentTimeMillis()
        if (ahora - ultimoRegistro < 10000) return
        val archivo = File(getApplication<Application>().filesDir, "bpm-$numero.cifrado")
        val lineas = if (archivo.exists()) archivo.readLines().takeLast(999) else emptyList()
        val lectura = Seguridad.cifrar("$ahora,$bpm", numero)
        archivo.writeText((lineas + lectura).joinToString("\n") + "\n")
        ultimoRegistro = ahora
    }

    fun detenerConsulta() { visible = false; consulta?.cancel(); consulta = null }

    fun desconectar() {
        consultar = false
        consulta?.cancel(); consulta = null
        interno.update { it.copy(conectado = false, datos = null, errorConexion = "") }
    }

    fun cerrarSesion() {
        desconectar()
        operacion?.cancel(); operacion = null
        ultimoRegistro = 0
        interno.value = EstadoPantalla(preparado = true, hayCuentas = true)
    }

    fun guardarUmbrales(bajo: Int, alto: Int) {
        val usuario = interno.value.usuario ?: return
        ejecutar {
            require(bajo in 30..150 && alto in 60..220 && bajo < alto) { "Umbrales inválidos" }
            withContext(Dispatchers.IO) {
                check(base.comprobarUsuario(usuario.numero).rol == "administrador") { "Acceso denegado" }
                Conexion(base.leerEquipo(usuario.numero)).peticion("/config", JSONObject().put("low", bajo).put("high", alto))
            }
            "Umbrales guardados en el ESP"
        }
    }

    fun enviarSos() {
        val usuario = interno.value.usuario ?: return
        ejecutar {
            val equipo = withContext(Dispatchers.IO) {
                check(base.comprobarUsuario(usuario.numero).rol == "monitoreado") { "Acceso denegado" }
                base.leerEquipo(usuario.numero)
            }
            avisar("Buscando ubicación hasta 8 segundos…")
            val ubicacion = getApplication<Application>().obtenerUbicacion()
            val cuerpo = JSONObject().put("kind", "manual")
            if (ubicacion != null) cuerpo.put("lat", ubicacion.latitude).put("lon", ubicacion.longitude).put("accuracy", ubicacion.accuracy.toDouble())
            avisar("Enviando SOS…")
            withContext(Dispatchers.IO) { Conexion(equipo).peticion("/alert", cuerpo) }
            if (ubicacion == null) "SOS publicado sin ubicación" else "SOS publicado con ubicación"
        }
    }

    private fun ejecutar(tarea: suspend () -> String) {
        if (operacion?.isActive == true) return
        interno.update { it.copy(ocupado = true) }
        operacion = viewModelScope.launch {
            try {
                val mensaje = tarea()
                interno.update { it.copy(ocupado = false, mensaje = mensaje) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                interno.update { it.copy(ocupado = false, mensaje = e.message ?: "No se pudo completar la operación") }
            }
        }
    }

    override fun onCleared() { base.close(); super.onCleared() }
}
