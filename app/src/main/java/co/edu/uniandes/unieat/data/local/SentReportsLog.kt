package co.edu.uniandes.unieat.data.local

import android.content.Context
import java.io.File

fun appendSentReport(context: Context, menuId: String, minutes: Int) {
    val file = File(context.filesDir, "wait_reports.log")
    file.appendText("${System.currentTimeMillis()},$menuId,$minutes\n")
}

fun clearSentReports(context: Context) {
    val file = File(context.filesDir, "wait_reports.log")
    file.delete()
}
