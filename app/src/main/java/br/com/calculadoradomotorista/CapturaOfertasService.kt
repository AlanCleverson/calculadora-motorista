package br.com.calculadoradomotorista

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.PackageManager
import android.os.Build

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CapturaOfertasService : AccessibilityService() {

    companion object {
        private const val CANAL_NOTIFICACOES = "captura_motorista"
        private const val ID_STATUS = 4101
        private const val ID_OFERTA = 4102

        private val PACOTES_CORRIDA = setOf(
            "com.ubercab.driver",
            "com.app99.driver"
        )
    }

    private var janela: WindowManager? = null
    private var caixa: TextView? = null

    private var ultimaOferta = ""
    private var horarioUltimaOferta = 0L
    private var ultimaLeitura = 0L
    private var statusNotificado = false

    data class DadosOferta(
        val aplicativo: String,
        val valor: Double,
        val minutosBusca: Double,
        val distanciaBusca: Double,
        val minutosViagem: Double,
        val distanciaViagem: Double,
        val avaliacao: Double?
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        janela = getSystemService(WINDOW_SERVICE) as WindowManager
        criarCanalNotificacoes()
        notificarStatus(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pacote = event.packageName?.toString() ?: return

        if (pacote !in PACOTES_CORRIDA) {
            removerCaixa()
            return
        }

        if (!statusNotificado) {
            notificarStatus(true)
        }

        val agora = SystemClock.elapsedRealtime()

        if (agora - ultimaLeitura < 500) return
        ultimaLeitura = agora

        if (
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
        ) return

        val raiz = rootInActiveWindow ?: return
        val textos = mutableListOf<String>()

        coletarTextos(raiz, textos)

        val textoCompleto = textos.joinToString("\n")
        val oferta = interpretarOferta(textoCompleto, pacote) ?: return

        val identificador = listOf(
            oferta.aplicativo,
            oferta.valor,
            oferta.distanciaBusca,
            oferta.distanciaViagem,
            oferta.minutosBusca,
            oferta.minutosViagem,
            oferta.avaliacao
        ).joinToString("|")

        val momento = SystemClock.elapsedRealtime()

        // Evita registrar repetidamente a mesma tela.
        // Se a mesma oferta reaparecer após 30 segundos, poderá ser registrada.
        if (
            identificador == ultimaOferta &&
            momento - horarioUltimaOferta < 30_000
        ) return

        ultimaOferta = identificador
        horarioUltimaOferta = momento

        salvarOferta(oferta, textoCompleto)
        mostrarCaixa(oferta)
        notificarNovaOferta(oferta)
    }

    private fun coletarTextos(
        no: AccessibilityNodeInfo,
        resultado: MutableList<String>,
        profundidade: Int = 0
    ) {
        if (profundidade > 20 || resultado.size > 500) return

        no.text?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resultado.add(it) }

        no.contentDescription?.toString()?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resultado.add(it) }

        for (i in 0 until no.childCount) {
            if (resultado.size > 500) break

            val filho = no.getChild(i) ?: continue

            coletarTextos(
                filho,
                resultado,
                profundidade + 1
            )
        }
    }

    private fun interpretarOferta(
        texto: String,
        pacote: String
    ): DadosOferta? {

        val valorRegex = Regex(
            """R\$\s*([0-9]+(?:[.,][0-9]{1,2})?)""",
            RegexOption.IGNORE_CASE
        )

        val valor = valorRegex.find(texto)
            ?.groupValues
            ?.get(1)
            ?.let { numero(it) }
            ?: return null

        val trechosTempo = Regex(
            """(\d+)\s*min(?:utos?)?\s*\(\s*([0-9]+(?:[.,][0-9]+)?)\s*km\s*\)""",
            RegexOption.IGNORE_CASE
        ).findAll(texto).toList()

        if (trechosTempo.size < 2) return null

        val busca = trechosTempo[0]
        val viagem = trechosTempo[1]

        val linhaAvaliacao = texto.lines().firstOrNull {
            it.contains("corrida", ignoreCase = true) ||
            it.contains("avalia", ignoreCase = true)
        }

        val avaliacao = linhaAvaliacao?.let {
            Regex("""([0-5][,.][0-9]+)""")
                .find(it)
                ?.groupValues
                ?.get(1)
                ?.let { n -> numero(n) }
        }

        val nomeApp = when (pacote) {
            "com.app99.driver" -> "99"
            "com.ubercab.driver" -> "Uber"
            else -> return null
        }

        return DadosOferta(
            aplicativo = nomeApp,
            valor = valor,
            minutosBusca = busca.groupValues[1].toDouble(),
            distanciaBusca = numero(busca.groupValues[2]),
            minutosViagem = viagem.groupValues[1].toDouble(),
            distanciaViagem = numero(viagem.groupValues[2]),
            avaliacao = avaliacao
        )
    }

    private fun numero(texto: String): Double {
        return texto.replace(",", ".").toDoubleOrNull() ?: 0.0
    }

    private fun classificar(
        valor: Double,
        referencia: Double,
        tolerancia: Double
    ): Nivel {
        val limiteInferior = referencia * (1.0 - tolerancia / 100.0)
        val limiteSuperior = referencia * (1.0 + tolerancia / 100.0)

        return when {
            valor < limiteInferior -> Nivel.VERMELHO
            valor <= limiteSuperior -> Nivel.AMARELO
            else -> Nivel.VERDE
        }
    }

    private fun classificarAvaliacao(
        valor: Double,
        referencia: Double,
        tolerancia: Double
    ): Nivel {
        return when {
            valor < referencia - tolerancia -> Nivel.VERMELHO
            valor <= referencia + tolerancia -> Nivel.AMARELO
            else -> Nivel.VERDE
        }
    }

    private fun calcularGeral(
        nivelKm: Nivel,
        nivelHora: Nivel,
        nivelAvaliacao: Nivel?
    ): Nivel {
        val pontos = mutableListOf(
            nivelKm.pontos,
            nivelHora.pontos
        )

        nivelAvaliacao?.let {
            pontos.add(it.pontos)
        }

        val media = pontos.average()

        return when {
            media >= 2.5 -> Nivel.VERDE
            media >= 1.5 -> Nivel.AMARELO
            else -> Nivel.VERMELHO
        }
    }

    private fun salvarOferta(
        oferta: DadosOferta,
        textoOriginal: String
    ) {
        val preferencias = getSharedPreferences(
            "motorista_config",
            MODE_PRIVATE
        )

        val distanciaTotal =
            oferta.distanciaBusca + oferta.distanciaViagem

        val tempoTotal =
            oferta.minutosBusca + oferta.minutosViagem

        if (distanciaTotal <= 0 || tempoTotal <= 0) return

        val porKm = oferta.valor / distanciaTotal
        val porHora = oferta.valor / (tempoTotal / 60.0)

        // Usa as mesmas referências configuradas na tela principal.
        val referenciaKm = preferencias.getString("km", "1.50")
            ?.let { numero(it) } ?: 1.50

        val referenciaHora = preferencias.getString("hora", "30.00")
            ?.let { numero(it) } ?: 30.00

        val referenciaAvaliacao =
            preferencias.getString("avaliacao", "4.85")
                ?.let { numero(it) } ?: 4.85

        val toleranciaDinheiro =
            preferencias.getString("tolerancia_dinheiro", "10")
                ?.let { numero(it) } ?: 10.0

        val toleranciaAvaliacao =
            preferencias.getString("tolerancia_avaliacao", "0.05")
                ?.let { numero(it) } ?: 0.05

        val nivelKm = classificar(
            porKm,
            referenciaKm,
            toleranciaDinheiro
        )

        val nivelHora = classificar(
            porHora,
            referenciaHora,
            toleranciaDinheiro
        )

        val nivelAvaliacao = oferta.avaliacao?.let {
            classificarAvaliacao(
                it,
                referenciaAvaliacao,
                toleranciaAvaliacao
            )
        }

        val nivelGeral = calcularGeral(
            nivelKm,
            nivelHora,
            nivelAvaliacao
        )

        val data = SimpleDateFormat(
            "dd/MM/yyyy HH:mm",
            Locale("pt", "BR")
        ).format(Date())

        // Mantém o mesmo formato de registros da calculadora manual.
        val registro = JSONObject().apply {
            put("data", data)
            put("valor", oferta.valor)
            put("distancia", distanciaTotal)
            put("minutos", tempoTotal)
            put("porKm", porKm)
            put("porHora", porHora)

            put(
                "avaliacao",
                oferta.avaliacao ?: JSONObject.NULL
            )

            put("nivelKm", nivelKm.name)
            put("nivelHora", nivelHora.name)

            put(
                "nivelAvaliacao",
                nivelAvaliacao?.name ?: JSONObject.NULL
            )

            put("nivelGeral", nivelGeral.name)
            put("origem", oferta.aplicativo)
        }

        // Histórico único: ofertas manuais + Uber + 99.
        val historicoAtual = preferencias.getString(
            "historico",
            "[]"
        ) ?: "[]"

        val historico = try {
            JSONArray(historicoAtual)
        } catch (_: Exception) {
            JSONArray()
        }

        historico.put(registro)

        preferencias.edit()
            .putString("historico", historico.toString())
            .apply()

        // Mantém também a última captura para diagnóstico.
        val dadosCaptura = JSONObject().apply {
            put("aplicativo", oferta.aplicativo)
            put("valor", oferta.valor)
            put("distanciaBusca", oferta.distanciaBusca)
            put("distanciaViagem", oferta.distanciaViagem)
            put("distanciaTotal", distanciaTotal)
            put("minutosBusca", oferta.minutosBusca)
            put("minutosViagem", oferta.minutosViagem)
            put("minutosTotal", tempoTotal)
            put("avaliacao", oferta.avaliacao ?: JSONObject.NULL)
            put("capturadaEm", System.currentTimeMillis())
            put("textoOriginal", textoOriginal)
        }

        getSharedPreferences(
            "motorista_capturas",
            MODE_PRIVATE
        ).edit()
            .putString("ultima_oferta", dadosCaptura.toString())
            .apply()
    }

    private fun mostrarCaixa(oferta: DadosOferta) {
        val distancia =
            oferta.distanciaBusca + oferta.distanciaViagem

        val tempo =
            oferta.minutosBusca + oferta.minutosViagem

        if (distancia <= 0 || tempo <= 0) return

        val porKm = oferta.valor / distancia
        val porHora = oferta.valor / (tempo / 60.0)

        val conteudo = buildString {
            append("${oferta.aplicativo} • NOVA OFERTA\n")
            append("R$ ${moeda(oferta.valor)}\n")
            append("${moeda(distancia)} km • ${tempo.toInt()} min\n")
            append("R$ ${moeda(porKm)}/km  |  R$ ${moeda(porHora)}/h")

            oferta.avaliacao?.let {
                append("\nNota: ${moeda(it)}")
            }
        }

        Handler(Looper.getMainLooper()).post {
            try {
                val texto = caixa ?: TextView(this).apply {
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(22, 18, 22, 18)
                    typeface = Typeface.DEFAULT_BOLD

                    background = GradientDrawable().apply {
                        setColor(Color.rgb(35, 65, 57))
                        cornerRadius = 24f
                        setStroke(2, Color.rgb(53, 185, 154))
                    }
                }

                texto.text = conteudo
                caixa = texto

                if (texto.parent == null) {
                    val parametros = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                        y = 100
                    }

                    janela?.addView(texto, parametros)
                } else {
                    janela?.updateViewLayout(
                        texto,
                        texto.layoutParams
                    )
                }
            } catch (_: Exception) {
                // A falha visual não deve interromper a captura.
            }
        }
    }


    private fun temPermissaoNotificacao(): Boolean {
        return Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun criarCanalNotificacoes() {
        if (Build.VERSION.SDK_INT >= 26) {
            val canal = NotificationChannel(
                CANAL_NOTIFICACOES,
                "Calculadora do Motorista",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Status da captura e novas ofertas"
                setSound(null, null)
                enableVibration(false)
            }

            val manager =
                getSystemService(NotificationManager::class.java)
                    ?: return

            manager.createNotificationChannel(canal)
        }
    }

    private fun notificarStatus(ativo: Boolean) {
        if (!temPermissaoNotificacao()) return

        try {
            criarCanalNotificacoes()

            val texto = if (ativo) {
                "Monitorando ofertas do Uber e 99."
            } else {
                "A captura foi interrompida. Verifique a acessibilidade."
            }

            val notificacao = Notification.Builder(
                this,
                CANAL_NOTIFICACOES
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(
                    if (ativo) "Captura automática ativa"
                    else "Captura automática interrompida"
                )
                .setContentText(texto)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(ativo)
                .setOnlyAlertOnce(true)
                
                .build()

            val manager =
                getSystemService(NotificationManager::class.java)
                    ?: return

            manager.notify(ID_STATUS, notificacao)
            statusNotificado = ativo
        } catch (_: Exception) {
            // A notificação não deve interromper a captura.
        }
    }

    private fun notificarNovaOferta(oferta: DadosOferta) {
        if (!temPermissaoNotificacao()) return

        try {
            val distancia =
                oferta.distanciaBusca + oferta.distanciaViagem
            val minutos =
                oferta.minutosBusca + oferta.minutosViagem

            val porKm = if (distancia > 0) {
                oferta.valor / distancia
            } else {
                0.0
            }

            val porHora = if (minutos > 0) {
                oferta.valor / (minutos / 60.0)
            } else {
                0.0
            }

            val detalhe = "R$ ${moeda(oferta.valor)} • " +
                "${moeda(distancia)} km • " +
                "R$ ${moeda(porKm)}/km • " +
                "R$ ${moeda(porHora)}/h"

            val notificacao = Notification.Builder(
                this,
                CANAL_NOTIFICACOES
            )
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Nova oferta detectada • ${oferta.aplicativo}")
                .setContentText(detalhe)
                .setStyle(
                    Notification.BigTextStyle().bigText(detalhe)
                )
                .setCategory(Notification.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
                
                .build()

            val manager =
                getSystemService(NotificationManager::class.java)
                    ?: return

            manager.notify(ID_OFERTA, notificacao)
        } catch (_: Exception) {
            // A notificação não deve interromper a captura.
        }
    }

    private fun moeda(valor: Double): String {
        return String.format(
            Locale("pt", "BR"),
            "%.2f",
            valor
        )
    }

    private fun removerCaixa() {
        Handler(Looper.getMainLooper()).post {
            try {
                caixa?.let {
                    janela?.removeView(it)
                }
            } catch (_: Exception) {
            }

            caixa = null
        }
    }

    override fun onInterrupt() {
        removerCaixa()
        notificarStatus(false)
    }

    override fun onDestroy() {
        removerCaixa()
        notificarStatus(false)
        super.onDestroy()
    }
}
