package br.com.calculadoradomotorista

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun AssistenteConfiguracao(
    context: Context,
    onAbrirParametros: () -> Unit,
    onConcluir: () -> Unit
) {
    var acessibilidadeAtiva by remember {
        mutableStateOf(verificarAcessibilidade(context))
    }

    var notificacoesAtivas by remember {
        mutableStateOf(verificarNotificacoes(context))
    }

    val solicitadorNotificacao =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { concedida ->
            notificacoesAtivas = concedida
        }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "Vamos configurar seu aplicativo",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            "Siga estas etapas para preparar a análise automática " +
                "das ofertas e personalizar seus critérios."
        )

        HorizontalDivider()

        Text("1. Acessibilidade", fontWeight = FontWeight.Bold)

        Text(
            "Essa autorização permite que o aplicativo leia os textos " +
                "visíveis das ofertas do Uber e 99 para calcular os " +
                "indicadores de rentabilidade. A leitura é feita no aparelho."
        )

        Text(
            if (acessibilidadeAtiva)
                "Acessibilidade: ativada"
            else
                "Acessibilidade: ainda não autorizada",
            color = if (acessibilidadeAtiva)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )

        Button(
            onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("ABRIR CONFIGURAÇÕES DE ACESSIBILIDADE")
        }

        OutlinedButton(
            onClick = {
                acessibilidadeAtiva = verificarAcessibilidade(context)
                notificacoesAtivas = verificarNotificacoes(context)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("VERIFICAR AUTORIZAÇÕES")
        }

        HorizontalDivider()

        Text("2. Janela flutuante", fontWeight = FontWeight.Bold)

        Text(
            "A janela que mostra os cálculos sobre o Uber ou 99 utiliza " +
                "a própria autorização de acessibilidade. Não precisa " +
                "de uma segunda permissão de sobreposição."
        )

        HorizontalDivider()

        Text("3. Notificações", fontWeight = FontWeight.Bold)

        Text(
            "A Calculadora poderá avisar quando a captura automática " +
                "estiver ativa e quando uma nova oferta for detectada."
        )

        Text(
            if (notificacoesAtivas)
                "Notificações: autorizadas"
            else
                "Notificações: ainda não autorizadas",
            color = if (notificacoesAtivas)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.error,
            fontWeight = FontWeight.Bold
        )

        if (Build.VERSION.SDK_INT >= 33) {
            Button(
                onClick = {
                    solicitadorNotificacao.launch(
                        Manifest.permission.POST_NOTIFICATIONS
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("AUTORIZAR NOTIFICAÇÕES")
            }
        } else {
            Text(
                "Nesta versão do Android, não é necessário solicitar " +
                    "a autorização em tempo de execução."
            )
        }

        HorizontalDivider()

        Text("4. Configure seus critérios", fontWeight = FontWeight.Bold)

        Text(
            "R$/km: sua meta de ganho por quilômetro, considerando " +
                "o deslocamento até o passageiro e a viagem."
        )

        Text(
            "R$/hora: sua meta de ganho por hora, considerando " +
                "todo o tempo estimado da corrida."
        )

        Text(
            "Avaliação: nota de referência desejada para o passageiro. " +
                "Esse indicador é opcional quando a oferta não apresenta nota."
        )

        Text(
            "Tolerância financeira: define a faixa amarela ao redor " +
                "das metas de R$/km e R$/hora."
        )

        Text(
            "Tolerância da avaliação: define a faixa amarela em pontos " +
                "ao redor da nota de referência."
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text("Exemplo de classificação", fontWeight = FontWeight.Bold)
                Text("Meta: R$ 1,50/km")
                Text("Tolerância: 10%")
                Text("Abaixo de R$ 1,35: vermelho")
                Text("De R$ 1,35 até R$ 1,65: amarelo")
                Text("Acima de R$ 1,65: verde")
            }
        }

        Button(
            onClick = onAbrirParametros,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CONFIGURAR MEUS PARÂMETROS")
        }

        OutlinedButton(
            onClick = {
                acessibilidadeAtiva = verificarAcessibilidade(context)
                notificacoesAtivas = verificarNotificacoes(context)
                onConcluir()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("CONCLUIR POR ENQUANTO")
        }

        Text(
            "Você poderá abrir este assistente novamente nas Configurações. " +
                "Se não autorizar a acessibilidade, ainda poderá utilizar " +
                "a análise manual.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun verificarAcessibilidade(context: Context): Boolean {
    val habilitados = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false

    val componente = ComponentName(
        context,
        CapturaOfertasService::class.java
    ).flattenToString()

    return habilitados.split(':').any {
        it.equals(componente, ignoreCase = true)
    }
}

private fun verificarNotificacoes(context: Context): Boolean {
    return Build.VERSION.SDK_INT < 33 ||
        context.checkSelfPermission(
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
}
