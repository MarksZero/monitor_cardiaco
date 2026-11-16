package com.example.monitorcardiaco

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {
    private lateinit var modelo: Vista
    private lateinit var caja: LinearLayout
    private lateinit var mensaje: TextView
    private var informe: TextView? = null
    private var lista: TextView? = null
    private var pantalla = ""
    private val botones = mutableListOf<Button>()

    private val permisoRed = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) modelo.conectar() else modelo.avisar("Concede acceso a la red local para consultar el ESP")
    }
    private val permisoUbicacion = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        modelo.enviarSos()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        modelo = ViewModelProvider(this)[Vista::class.java]
        val desplazamiento = ScrollView(this)
        caja = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        desplazamiento.addView(caja)
        ViewCompat.setOnApplyWindowInsetsListener(desplazamiento) { vista, bordes ->
            val espacio = bordes.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            vista.setPadding(espacio.left, espacio.top, espacio.right, espacio.bottom)
            bordes
        }
        setContentView(desplazamiento)
        mensaje = texto("Abriendo cuentas…")
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                modelo.estado.collect { estado ->
                    val nueva = when {
                        !estado.preparado -> "cargando"
                        !estado.hayCuentas -> "registro"
                        estado.usuario == null -> "ingreso"
                        else -> "${estado.usuario.numero}:${estado.usuario.rol}"
                    }
                    if (nueva != pantalla) {
                        pantalla = nueva
                        caja.removeAllViews(); botones.clear(); lista = null; informe = null
                        when (nueva) {
                            "cargando" -> mensaje = texto("Abriendo cuentas…")
                            "registro" -> mostrarRegistro()
                            "ingreso" -> mostrarIngreso()
                            else -> mostrarUsuario(checkNotNull(estado.usuario))
                        }
                    }
                    mensaje.text = listOf(estado.mensaje, estado.errorConexion).filter { it.isNotEmpty() }.joinToString("\n")
                    botones.forEach { it.isEnabled = !estado.ocupado }
                    lista?.text = estado.usuarios.joinToString("\n") {
                        "${it.nombre} · ${it.rol} · ${if (it.activo) "habilitado" else "deshabilitado"}"
                    }
                    mostrarDatos(estado)
                }
            }
        }
    }

    override fun onStart() { super.onStart(); modelo.empezarConsulta() }
    override fun onStop() { modelo.detenerConsulta(); super.onStop() }

    private fun texto(contenido: String, tamano: Float = 16f) = TextView(this).apply {
        text = contenido; textSize = tamano; setPadding(0, 12, 0, 12); caja.addView(this)
    }

    private fun campo(titulo: String, clave: Boolean = false) = EditText(this).apply {
        hint = titulo; isSingleLine = true
        inputType = if (clave) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
        // No guardar contraseñas en el estado de restauración de la Activity.
        isSaveEnabled = false
        caja.addView(this)
    }

    private fun boton(titulo: String, accion: () -> Unit) = Button(this).apply {
        text = titulo; setOnClickListener { accion() }; caja.addView(this); botones.add(this)
    }

    private fun mostrarRegistro() {
        texto("Crear administrador", 24f)
        texto("Configuración inicial de este teléfono. No necesita conexión con el ESP. No hay cuentas predeterminadas.")
        val nombre = campo("Nombre del administrador")
        val clave = campo("Contraseña: al menos 15 caracteres", true)
        val repetida = campo("Repetir contraseña", true)
        boton("Crear administrador") {
            modelo.crearAdministrador(nombre.text.toString(), clave.text.toString(), repetida.text.toString())
            clave.text.clear(); repetida.text.clear()
        }
        mensaje = texto("")
    }

    private fun mostrarIngreso() {
        texto("Iniciar sesión", 24f)
        texto("Usa una cuenta creada en este teléfono. La contraseña de la aplicación no es el token del ESP.")
        val nombre = campo("Usuario")
        val clave = campo("Contraseña", true)
        boton("Entrar") {
            modelo.iniciarSesion(nombre.text.toString(), clave.text.toString())
            clave.text.clear()
        }
        mensaje = texto("")
    }

    private fun mostrarUsuario(usuario: Usuario) {
        texto("${usuario.nombre} · ${usuario.rol}", 24f)
        mensaje = texto("")
        if (usuario.rol == "administrador") mostrarAdministracion()
        texto("Conexión con el equipo", 20f)
        texto("Es independiente del inicio de sesión. Requiere configurar credenciales del ESP y un firmware compatible.")
        boton("Conectar / probar ESP") {
            if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission("android.permission.ACCESS_LOCAL_NETWORK") != PackageManager.PERMISSION_GRANTED)
                permisoRed.launch("android.permission.ACCESS_LOCAL_NETWORK")
            else modelo.conectar()
        }
        boton("Desconectar ESP") { modelo.desconectar() }
        informe = texto("")
        if (usuario.rol == "monitoreado") {
            boton("Enviar SOS") {
                permisoUbicacion.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }
            texto("SOS se envía aun sin ubicación si el ESP está accesible. Publicación no significa atención del familiar.")
        }
        if (usuario.rol == "familiar") texto("Mantén esta pantalla abierta para consultar alertas cada 2 segundos. No hay recepción garantizada en segundo plano.")
        if (usuario.rol == "administrador") {
            val bajo = campo("Umbral inferior (30–150)")
            val alto = campo("Umbral superior (60–220)")
            boton("Guardar umbrales en el ESP") {
                val inferior = bajo.text.toString().toIntOrNull()
                val superior = alto.text.toString().toIntOrNull()
                if (inferior == null || superior == null) modelo.avisar("Escribe ambos umbrales como números")
                else modelo.guardarUmbrales(inferior, superior)
            }
        }
        boton("Cerrar sesión") { modelo.cerrarSesion() }
    }

    private fun mostrarAdministracion() {
        texto("Cuentas de este teléfono", 20f)
        lista = texto("")
        val nombre = campo("Nombre de cuenta")
        val clave = campo("Contraseña nueva; vacía conserva la anterior", true)
        val selector = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, roles)
            setSelection(roles.indexOf("familiar"))
            caja.addView(this)
        }
        val activo = CheckBox(this).apply { text = "Cuenta habilitada"; isChecked = true; caja.addView(this) }
        boton("Cargar cuenta para editar") {
            val usuario = modelo.estado.value.usuarios.find { it.nombre == nombre.text.toString().trim().lowercase(Locale.ROOT) }
            if (usuario == null) modelo.avisar("No existe esa cuenta")
            else { selector.setSelection(roles.indexOf(usuario.rol)); activo.isChecked = usuario.activo; clave.text.clear() }
        }
        boton("Guardar cuenta y rol") {
            modelo.guardarUsuario(nombre.text.toString(), clave.text.toString(), selector.selectedItem.toString(), activo.isChecked)
            clave.text.clear()
        }
        boton("Eliminar cuenta") {
            val usuario = modelo.estado.value.usuarios.find { it.nombre == nombre.text.toString().trim().lowercase(Locale.ROOT) }
            if (usuario == null) modelo.avisar("No existe esa cuenta")
            else AlertDialog.Builder(this).setTitle("Eliminar ${usuario.nombre}")
                .setMessage("Se eliminará la cuenta de este teléfono. No modifica otros teléfonos ni revoca la clave del ESP.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Eliminar") { _, _ -> modelo.eliminarUsuario(usuario.numero) }.show()
        }
        texto("No puedes editar ni eliminar tu propia cuenta. Otra cuenta administradora puede ayudarte a cambiarla.")
        texto("Configurar acceso al ESP para una cuenta", 20f)
        texto("Las credenciales deben existir previamente en un firmware con autenticación. El firmware del informe aún no las admite.")
        val destino = campo("Nombre de la cuenta local")
        val ip = campo("IPv4 privada del ESP")
        val usuarioEsp = campo("Usuario del ESP")
        val token = campo("Token del ESP: 64 caracteres hexadecimales", true)
        boton("Guardar conexión sin probar") {
            modelo.guardarEquipo(destino.text.toString(), ip.text.toString().trim(), usuarioEsp.text.toString().trim(), token.text.toString().trim())
            token.text.clear()
        }
    }

    private fun mostrarDatos(estado: EstadoPantalla) {
        val salida = informe ?: return
        if (!estado.conectado) { salida.text = "Sin medición actual del ESP. Las cuentas locales siguen disponibles."; return }
        val datos = estado.datos ?: return
        val lineas = mutableListOf("Conectado")
        lineas.add(if (datos.getBoolean("signal")) "${datos.getInt("bpm")} BPM" else "Sin señal de pulso válida")
        val alerta = datos.optJSONObject("alert")
        if (alerta == null) lineas.add("Sin alertas desde el arranque")
        else {
            val segundos = ((datos.getLong("uptime") - alerta.getLong("uptime")) and 0xffffffffL) / 1000
            lineas.add("Última alerta: ${alerta.getString("kind")} · hace ${segundos}s")
            lineas.add(if (alerta.has("lat")) "${alerta.getDouble("lat")}, ${alerta.getDouble("lon")} (±${alerta.getDouble("accuracy")} m)" else "Sin ubicación")
        }
        if (estado.usuario?.rol == "administrador") {
            lineas.add("ADC: ${datos.optInt("raw")} · amplitud: ${datos.optInt("amplitude")}")
            lineas.add("Umbrales: ${datos.optInt("low")}–${datos.optInt("high")}")
        }
        salida.text = lineas.joinToString("\n")
    }
}
