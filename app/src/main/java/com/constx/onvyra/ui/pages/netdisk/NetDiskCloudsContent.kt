package com.constx.onvyra.ui.pages.netdisk

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.constx.onvyra.data.netdisk.NetDiskPlatform
import com.constx.onvyra.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.rerere.hugeicons.HugeIcons
import me.rerere.hugeicons.stroke.MoreVertical

/** The existing provider accounts, without treating a locally saved token as a verified login. */
@Composable
internal fun NetDiskCloudsContent(
    vm: NetDiskViewModel,
    onSelect: (NetDiskPlatform) -> Unit,
    modifier: Modifier = Modifier,
    onManage: (NetDiskPlatform) -> Unit = onSelect,
) {
    var configured by remember(vm) { mutableStateOf(emptySet<NetDiskPlatform>()) }
    var reading by remember(vm) { mutableStateOf(true) }
    var readError by remember(vm) { mutableStateOf(false) }
    var refresh by remember(vm) { mutableStateOf(0) }
    LaunchedEffect(vm, vm.credentialRevision, refresh) {
        reading = true
        readError = false
        try {
            configured = withContext(Dispatchers.IO) {
                vm.repository.platforms.filter { vm.repository.credential(it).isNotBlank() }.toSet()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            readError = true
        } finally {
            reading = false
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().testTag("netdisk-clouds"),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (readError) item("credential-error") {
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text("本机凭据状态读取失败，可重试或重新登录。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = { refresh++ }) { Text("重试") }
                }
            }
        }
        items(vm.repository.platforms, key = { it.name }) { platform ->
            val status = when {
                reading -> "读取账号状态…"
                readError -> "状态未读取"
                platform in configured -> "已保存本地凭据"
                else -> "未登录"
            }
            NetDiskCloudRow(platform, status, onSelect = { onSelect(platform) }, onManage = { onManage(platform) })
        }
    }
}

@Composable
private fun NetDiskCloudRow(platform: NetDiskPlatform, status: String, onSelect: () -> Unit, onManage: () -> Unit) {
    var menu by remember(platform) { mutableStateOf(false) }
    Surface(
        onClick = onSelect,
        modifier = Modifier.fillMaxWidth().testTag("netdisk-cloud-${platform.name}"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(0.6.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f)),
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp)
                .padding(start = 12.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            NetDiskProviderBadge(platform)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(platform.label, style = MaterialTheme.typography.titleMedium,
                    fontSize = 16.sp, lineHeight = 20.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(status, style = MaterialTheme.typography.bodySmall, fontSize = 12.sp, lineHeight = 16.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Box {
                IconButton(onClick = { menu = true },
                    modifier = Modifier.testTag("netdisk-cloud-menu-${platform.name}")) {
                    Icon(HugeIcons.MoreVertical, "管理${platform.label}",
                        modifier = Modifier.size(22.dp).rotate(90f),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("查看文件") }, onClick = { menu = false; onSelect() })
                    DropdownMenuItem(text = { Text("账号与登录") }, onClick = { menu = false; onManage() })
                }
            }
        }
    }
}

/** Bundled provider marks from the supplied YunGet reference; never fetched over the network. */
@Composable
internal fun NetDiskProviderBadge(platform: NetDiskPlatform, modifier: Modifier = Modifier.size(36.dp)) {
    val logo = when (platform) {
        NetDiskPlatform.LANZOU -> R.drawable.netdisk_logo_lanzou
        NetDiskPlatform.ILANZOU -> R.drawable.netdisk_logo_ilanzou
        NetDiskPlatform.PAN123 -> R.drawable.netdisk_logo_123pan
        NetDiskPlatform.QUARK -> R.drawable.netdisk_logo_quark
        NetDiskPlatform.UC -> R.drawable.netdisk_logo_uc
        NetDiskPlatform.BAIDU -> R.drawable.netdisk_logo_baidu
        NetDiskPlatform.XUNLEI -> R.drawable.netdisk_logo_xunlei
        NetDiskPlatform.PAN115 -> R.drawable.netdisk_logo_pan115
        NetDiskPlatform.C139 -> R.drawable.netdisk_logo_c139
        NetDiskPlatform.GUANGYA -> R.drawable.netdisk_logo_guangya
        NetDiskPlatform.ALIYUN -> null
    }
    if (logo != null) {
        Image(painterResource(logo), contentDescription = null, modifier = modifier)
        return
    }
    val (label, color) = when (platform) {
        NetDiskPlatform.LANZOU -> "蓝" to Color(0xFF249FE8)
        NetDiskPlatform.ILANZOU -> "优" to Color(0xFF38AA8C)
        NetDiskPlatform.PAN123 -> "123" to Color(0xFF3479EE)
        NetDiskPlatform.ALIYUN -> "A" to Color(0xFF7567F5)
        NetDiskPlatform.QUARK -> "Q" to Color(0xFF347BEB)
        NetDiskPlatform.UC -> "UC" to Color(0xFFFF8B20)
        NetDiskPlatform.BAIDU -> "度" to Color(0xFF3576EE)
        NetDiskPlatform.XUNLEI -> "迅" to Color(0xFF4285F5)
        NetDiskPlatform.PAN115 -> "115" to Color(0xFF274E87)
        NetDiskPlatform.C139 -> "移" to Color(0xFF44A887)
        NetDiskPlatform.GUANGYA -> "鸭" to Color(0xFFEAA52D)
    }
    Surface(modifier = modifier, shape = RoundedCornerShape(10.dp), color = color) {
        Box(contentAlignment = Alignment.Center) {
            Text(label, color = Color.White, fontSize = if (label.length > 2) 15.sp else 18.sp,
                fontWeight = FontWeight.Bold)
        }
    }
}
