package com.masitasia.chatglobo

import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import org.bukkit.Bukkit
import org.bukkit.Color
import org.bukkit.GameMode
import org.bukkit.Location
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Display
import org.bukkit.entity.EntityType
import org.bukkit.entity.Player
import org.bukkit.entity.TextDisplay
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.plugin.java.JavaPlugin
import java.util.*

class ChatGlobo : JavaPlugin(), Listener {

    // --- CONSTANTES ---
    private val anchoGlobo = 200

    // --- VARIABLES CONFIG ---
    private var globalActivo = true
    private var alturaGlobo = 0.25
    private var tiempoVida = 5
    private var ticksAparicion: Long = 1

    // --- LISTAS ---
    private val usuariosOcultos = mutableSetOf<UUID>()
    private val usuariosMuteados = mutableSetOf<UUID>()
    private val globosEstaticos = mutableSetOf<UUID>()

    // --- SERIALIZADOR ---
    private val serializer = LegacyComponentSerializer.legacyAmpersand()

    // --- Inicio y cierre ---
    override fun onEnable() {
        saveDefaultConfig()
        cargarConfiguracion()

        server.pluginManager.registerEvents(this, this)
        logger.info("¡ChatGlobo cargado! Versión Regex Correctora.")
    }

    override fun onDisable() {
        limpiarTodosLosGlobos()
        guardarDatos()
    }

    // --- Lógica de carga de configuración ---
    private fun cargarConfiguracion() {
        reloadConfig()

        globalActivo = config.getBoolean("global-activo", true)
        alturaGlobo = config.getDouble("altura-globo", 0.25)
        tiempoVida = config.getInt("tiempo-vida", 5)
        ticksAparicion = config.getLong("ticks-aparicion", 1)

        if (ticksAparicion < 0) ticksAparicion = 0

        usuariosOcultos.clear()
        usuariosMuteados.clear()
        cargarLista("usuarios-ocultos", usuariosOcultos)
        cargarLista("usuarios-muteados", usuariosMuteados)
    }

    private fun cargarLista(path: String, setDestino: MutableSet<UUID>) {
        val lista = config.getStringList(path)
        for (idString in lista) {
            try {
                setDestino.add(UUID.fromString(idString))
            } catch (e: IllegalArgumentException) { /* Ignorar */ }
        }
    }

    private fun guardarDatos() {
        config.set("global-activo", globalActivo)
        config.set("altura-globo", alturaGlobo)
        config.set("tiempo-vida", tiempoVida)
        config.set("ticks-aparicion", ticksAparicion)

        config.set("usuarios-ocultos", usuariosOcultos.map { it.toString() })
        config.set("usuarios-muteados", usuariosMuteados.map { it.toString() })

        saveConfig()
    }

    // --- Comandos ---
    override fun onCommand(sender: CommandSender, command: Command, label: String, args: Array<out String>): Boolean {

        when (command.name.lowercase()) {
            // Debugger
            "globodebug" -> {
                if (sender !is Player) return true

                sender.sendMessage("§e--- 1. BUSCANDO PERMISOS TEMPORALES ---")
                val perms = sender.effectivePermissions
                var encontroPermiso = false
                perms.forEach { permInfo ->
                    val nodo = permInfo.permission.lowercase()
                    // Buscamos cualquier permiso que contenga la palabra vanish o ultrastaff
                    if (nodo.contains("vanish") || nodo.contains("ultrastaff")) {
                        sender.sendMessage("§f- Permiso: §b${permInfo.permission} §7(Activo: ${permInfo.value})")
                        encontroPermiso = true
                    }
                }
                if (!encontroPermiso) sender.sendMessage("§cNo se detectaron permisos de vanish.")

                sender.sendMessage("§e--- 2. ESTADO DE BUKKIT NATIVO ---")
                sender.sendMessage("§f- Ignora dormir (SleepIgnored): §b${sender.isSleepingIgnored}")
                sender.sendMessage("§f- Es colisionable (Collidable): §b${sender.isCollidable}")

                return true
            }

            // Recargar configuración
            "globoreload" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                cargarConfiguracion()
                sender.sendMessage(Component.text("✅ Configuración recargada correctamente.", NamedTextColor.GREEN))
                return true
            }

            // Crear globo estático
            "globospawn" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                if (sender !is Player) return true
                if (args.isEmpty()) return true

                val bloqueMirado = sender.getTargetBlockExact(20)
                val spawnLoc = bloqueMirado?.location?.add(0.5, 1.5, 0.5) ?: sender.location.add(0.0, 1.5, 0.0)

                val textoCrudo = args.joinToString(" ")
                val mensaje = serializer.deserialize(textoCrudo)

                spawnGloboEstatico(spawnLoc, mensaje)
                return true
            }

            // Limpiar todos los globos
            "globoclear" -> {
                if (!sender.hasPermission("chatglobo.admin")) return true
                val eliminados = limpiarTodosLosGlobos()
                sender.sendMessage(Component.text("🎈 Eliminados $eliminados globos.", NamedTextColor.GREEN))
                return true
            }

            // Activar/Desactivar el plugin
            "globoglobal" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                globalActivo = !globalActivo
                guardarDatos()
                sender.sendMessage(Component.text("🎈 Global: ${if (globalActivo) "ON" else "OFF"}", if (globalActivo) NamedTextColor.GREEN else NamedTextColor.RED))
                if (!globalActivo) limpiarTodosLosGlobos()
                return true
            }

            // Altura del globo en bloques respecto a la cabeza del jugador
            "globoaltura" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                if (args.isEmpty()) return true
                alturaGlobo = args[0].toDoubleOrNull() ?: return true
                guardarDatos()
                sender.sendMessage(Component.text("🎈 Altura base: $alturaGlobo", NamedTextColor.GREEN))
                return true
            }

            // Tiempo de vida de los globos
            "globotiempo" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                if (args.isEmpty()) return true
                val valTiempo = args[0].toIntOrNull() ?: return true
                tiempoVida = valTiempo.coerceAtLeast(1)
                guardarDatos()
                sender.sendMessage(Component.text("🎈 Tiempo vida: ${tiempoVida}s", NamedTextColor.GREEN))
                return true
            }

            // Modificar el delay de aparición de los globos
            "globodelay" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                if (args.isEmpty()) {
                    sender.sendMessage(Component.text("Uso: /globodelay <ticks> (20 ticks = 1 seg)", NamedTextColor.RED))
                    return true
                }
                val valDelay = args[0].toLongOrNull() ?: return true
                ticksAparicion = valDelay.coerceAtLeast(0)
                guardarDatos()
                sender.sendMessage(Component.text("🎈 Delay aparición: $ticksAparicion ticks", NamedTextColor.GREEN))
                return true
            }

            // Silenciar los globos de un jugador
            "globomute" -> {
                if (!sender.hasPermission("chatglobo.admin")) return noPermiso(sender)
                if (args.isEmpty()) return true
                val target = Bukkit.getPlayer(args[0]) ?: return true
                val id = target.uniqueId

                if (usuariosMuteados.contains(id)) {
                    usuariosMuteados.remove(id)
                    sender.sendMessage(Component.text("🎈 DESMUTEADO: ${target.name}", NamedTextColor.GREEN))
                } else {
                    usuariosMuteados.add(id)
                    sender.sendMessage(Component.text("🎈 MUTEADO: ${target.name}", NamedTextColor.RED))
                }
                guardarDatos()
                return true
            }

            // Desactivar/Activar el globo para el jugador
            "globo" -> {
                if (sender !is Player) return true
                val id = sender.uniqueId

                if (usuariosOcultos.contains(id)) {
                    usuariosOcultos.remove(id)
                    sender.sendMessage(Component.text("🎈 ACTIVADO.", NamedTextColor.GREEN))
                } else {
                    usuariosOcultos.add(id)
                    sender.sendMessage(Component.text("🎈 DESACTIVADO.", NamedTextColor.YELLOW))
                }
                guardarDatos()
                return true
            }
        }
        return false
    }

    private fun noPermiso(sender: CommandSender): Boolean {
        sender.sendMessage(Component.text("No tienes permiso.", NamedTextColor.RED))
        return true
    }

    // --- Evento de chat ---
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    fun alChatear(event: AsyncChatEvent) {
        if (!globalActivo) return
        val player = event.player
        if (usuariosMuteados.contains(player.uniqueId)) return
        if (usuariosOcultos.contains(player.uniqueId)) return

        var mensajeDelChat = event.message()

        // --- FILTRO DE LIMPIEZA INTERACTIVE CHAT ---
        var textoSerializado = serializer.serialize(mensajeDelChat)

        val patronInteractiveChat = "<chat=[^:]+:((?:(?!:>).)+):>".toRegex()

        if (patronInteractiveChat.containsMatchIn(textoSerializado)) {
            textoSerializado = textoSerializado.replace(patronInteractiveChat, "$1")

            val patronMencionIC = "<IC\\^([^>]+)>".toRegex()
            textoSerializado = textoSerializado.replace(patronMencionIC, "$1")

            mensajeDelChat = serializer.deserialize(textoSerializado)
        }

        // --- CONSTRUCCIÓN DEL GLOBO ---
        val mensajeFinal = Component.text()
            .append(player.displayName())
            .append(Component.text(" dice: ", NamedTextColor.GRAY))
            .append(mensajeDelChat) // El mensaje ya limpio
            .build()

        server.scheduler.runTask(this, Runnable { spawnGloboJugador(player, mensajeFinal) })
    }

    // --- Limpieza ---
    private fun limpiarTodosLosGlobos(): Int {
        var count = 0
        for (p in Bukkit.getOnlinePlayers()) {
            for (pas in p.passengers) {
                if (pas is TextDisplay) {
                    pas.remove()
                    count++
                }
            }
        }
        for (uuid in globosEstaticos) {
            val e = Bukkit.getEntity(uuid)
            if (e != null && !e.isDead) {
                e.remove()
                count++
            }
        }
        globosEstaticos.clear()
        return count
    }

    // --- Helper creación de globos ---
    private fun crearGloboBase(location: Location, texto: Component): TextDisplay {
        val display = location.world.spawnEntity(location, EntityType.TEXT_DISPLAY) as TextDisplay
        display.text(texto)
        display.lineWidth = anchoGlobo
        display.backgroundColor = Color.fromARGB(160, 0, 0, 0)
        display.alignment = TextDisplay.TextAlignment.CENTER
        display.billboard = Display.Billboard.CENTER
        return display
    }

    // --- Comprobación de Vanish (Soporte universal para UltraStaff, Essentials, etc.) ---
    private fun isVanished(player: Player): Boolean {
        if (player.hasMetadata("vanished") || player.hasMetadata("vanish")) {
            return true
        }

        for (onlinePlayer in Bukkit.getOnlinePlayers()) {
            if (onlinePlayer != player && !onlinePlayer.canSee(player)) {
                return true
            }
        }
        return false
    }

    // --- Spawn de globo para jugador ---
    private fun spawnGloboJugador(player: Player, textoComponent: Component) {
        if (player.gameMode == GameMode.SPECTATOR || isVanished(player) || player.isInvisible) return

        // Limpiar anterior
        player.passengers.filterIsInstance<TextDisplay>().forEach { it.remove() }

        // 1. Nace invisible
        val display = player.world.spawn(player.location, TextDisplay::class.java) { entity ->
            entity.isVisibleByDefault = false
            entity.text(textoComponent)
            entity.lineWidth = anchoGlobo
            entity.backgroundColor = Color.fromARGB(160, 0, 0, 0)
            entity.alignment = TextDisplay.TextAlignment.CENTER
            entity.billboard = Display.Billboard.CENTER

            val transformation = entity.transformation
            transformation.translation.set(0f, alturaGlobo.toFloat(), 0f)
            entity.transformation = transformation
        }

        player.addPassenger(display)

        // 2. Aparecer con delay
        server.scheduler.runTaskLater(this, Runnable {
            if (!display.isDead && player.isOnline) {
                Bukkit.getOnlinePlayers().forEach { it.showEntity(this, display) }
            }
        }, ticksAparicion)

        // 3. Programar muerte
        server.scheduler.runTaskLater(this, Runnable { display.remove() }, (tiempoVida * 20L) + ticksAparicion)
    }

    // --- Spawn de globo estático ---
    private fun spawnGloboEstatico(location: Location, textoComponent: Component) {
        val display = crearGloboBase(location, textoComponent)
        display.isPersistent = false
        globosEstaticos.add(display.uniqueId)

        server.scheduler.runTaskLater(this, Runnable {
            if (!display.isDead) {
                display.remove()
                globosEstaticos.remove(display.uniqueId)
            }
        }, tiempoVida * 20L)
    }
}