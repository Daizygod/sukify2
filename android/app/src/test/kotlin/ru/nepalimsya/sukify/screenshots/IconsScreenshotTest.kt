package ru.nepalimsya.sukify.screenshots

import android.app.Application
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import ru.nepalimsya.sukify.ui.icons.SukifyIcons

/** Все иконки крупно — сверить заливку с тем, как их рисует браузер. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w1100dp-h2000dp-xxhdpi", application = Application::class)
class IconsScreenshotTest {

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun allIcons() {
        val icons = SukifyIcons::class.java.declaredMethods
            .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == ImageVector::class.java }
            .sortedBy { it.name }
            .map { it.name.removePrefix("get") to it.invoke(SukifyIcons) as ImageVector }
        captureRoboImage("screenshots/icons_android.png") {
            Column(Modifier.background(Color.White).padding(8.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    icons.forEach { (name, icon) ->
                        Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(96.dp).background(Color(0xFFEFEFEF)), contentAlignment = Alignment.Center) {
                                Icon(icon, null, tint = Color.Black, modifier = Modifier.size(88.dp))
                            }
                            Text(name, fontSize = 11.sp, color = Color.Black, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}
