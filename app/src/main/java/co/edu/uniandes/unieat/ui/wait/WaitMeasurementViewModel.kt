package co.edu.uniandes.unieat.ui.wait

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.data.local.waitMinutes
import co.edu.uniandes.unieat.data.local.clearArrival
import co.edu.uniandes.unieat.data.local.clearPendingMinutes
import co.edu.uniandes.unieat.data.local.hasFreshArrival
import co.edu.uniandes.unieat.data.local.readArrival
import co.edu.uniandes.unieat.data.local.readMenuId
import co.edu.uniandes.unieat.data.local.readPendingMenuId
import co.edu.uniandes.unieat.data.local.readPendingMinutes
import co.edu.uniandes.unieat.data.local.readPendingVersion
import co.edu.uniandes.unieat.data.local.readVersion
import co.edu.uniandes.unieat.data.local.saveArrival
import co.edu.uniandes.unieat.data.local.savePendingMinutes
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.ReportRepository

class WaitMeasurementViewModel(private val reportRepository: ReportRepository) : ViewModel() {

    private val _canFinish = MutableStateFlow(false)
    val canFinish: StateFlow<Boolean> = _canFinish.asStateFlow()

    fun saveArrivalVM(context: android.content.Context, millis: Long, menuId: String, version: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            saveArrival(context, millis, menuId, version)
            _canFinish.value = hasFreshArrival(context, menuId, System.currentTimeMillis())
        }
    }

    fun refreshCanFinish(context: android.content.Context, menuId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _canFinish.value = hasFreshArrival(context, menuId, System.currentTimeMillis())
        }
    }

    fun readArrivalVM(context: android.content.Context, callback: (Long?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val arrival = readArrival(context)
            callback(arrival)
        }
    }

    fun clearArrivalVM(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            clearArrival(context)
        }
    }

    fun readPendingMinutesVM(context: android.content.Context, callback: (Int?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val pendingMinutes = readPendingMinutes(context)
            callback(pendingMinutes)
        }
    }

    fun clearPendingMinutesVM(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            clearPendingMinutes(context)
        }
    }

    fun calculateWaitMinutes(start: Long, end: Long): Int? {
        return waitMinutes(start, end)
    }

    fun finishMeasurement(context: android.content.Context) {
        _canFinish.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val arrival = readArrival(context)
            val menuId = readMenuId(context)
            val version = readVersion(context)
            if (arrival != null && menuId != null && version != null) {
                val minutes = calculateWaitMinutes(arrival, System.currentTimeMillis())
                if (minutes != null) {
                    savePendingMinutes(context, minutes, menuId, version)
                }
                clearArrival(context)
            }
        }
    }

    fun tryAgain(context: android.content.Context) {
        readPendingMinutesVM(context) { minutes ->
            if (minutes == null) return@readPendingMinutesVM
            viewModelScope.launch(Dispatchers.IO) {
                val menuId = readPendingMenuId(context) ?: return@launch
                val version = readPendingVersion(context) ?: return@launch
                val body = ReportBody(
                    publicationId = menuId,
                    version = version,
                    kind = ReportKind.LONG_LINE,
                    observedWaitMinutes = minutes,
                )
                try {
                    reportRepository.submit(body)
                    clearPendingMinutesVM(context)
                } catch (e: ApiException) {
                }
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                WaitMeasurementViewModel(app.container.reportRepository)
            }
        }
    }

}
