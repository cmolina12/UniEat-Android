package co.edu.uniandes.unieat.ui.wait

import android.util.Log
import android.widget.Toast
import androidx.collection.LruCache
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import co.edu.uniandes.unieat.UniEatApplication
import co.edu.uniandes.unieat.core.model.ReportBody
import co.edu.uniandes.unieat.core.model.ReportKind
import co.edu.uniandes.unieat.data.local.MAX_ARRIVAL_AGE_MILLIS
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

class WaitMeasurementViewModel(
    private val reportRepository: ReportRepository,
    private val arrivalCache: LruCache<String, Long>,
) : ViewModel() {

    private val cacheMutex = Mutex()

    private val canFinishState = MutableStateFlow(false)
    val canFinish: StateFlow<Boolean> = canFinishState.asStateFlow()

    fun saveArrivalVM(context: android.content.Context, millis: Long, menuId: String, version: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheMutex.withLock {
                saveArrival(context, millis, menuId, version)
                arrivalCache.evictAll()
                canFinishState.value = hasFreshArrival(context, menuId, System.currentTimeMillis())
            }
        }
    }

    fun refreshCanFinish(context: android.content.Context, menuId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            cacheMutex.withLock {
                var arrivedAt = arrivalCache.get(menuId)
                if (arrivedAt != null) {
                    Log.d("ArrivalCache", "hit $menuId")
                } else {
                    Log.d("ArrivalCache", "miss $menuId")
                    val saved = readArrival(context)
                    arrivedAt = if (saved != null && readMenuId(context) == menuId) saved else 0L
                    arrivalCache.put(menuId, arrivedAt)
                }
                canFinishState.value = arrivedAt != 0L && System.currentTimeMillis() - arrivedAt < MAX_ARRIVAL_AGE_MILLIS
            }
        }
    }

    fun calculateWaitMinutes(start: Long, end: Long): Int? {
        return waitMinutes(start, end)
    }

    fun finishMeasurement(context: android.content.Context) {
        canFinishState.value = false
        viewModelScope.launch(Dispatchers.IO) {
            val arrivalDeferred = async { readArrival(context) }
            val menuIdDeferred = async { readMenuId(context) }
            val versionDeferred = async { readVersion(context) }
            val arrival = arrivalDeferred.await()
            val menuId = menuIdDeferred.await()
            val version = versionDeferred.await()
            if (arrival != null && menuId != null && version != null) {
                val minutes = calculateWaitMinutes(arrival, System.currentTimeMillis())
                if (minutes != null) {
                    savePendingMinutes(context, minutes, menuId, version)
                }
                cacheMutex.withLock {
                    clearArrival(context)
                    arrivalCache.evictAll()
                }
            }
        }
    }

    fun tryAgain(context: android.content.Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val minutes = readPendingMinutes(context) ?: return@launch
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
                clearPendingMinutes(context)
                withContext(Dispatchers.Main) {
                    Toast.makeText(context.applicationContext, "Tu medición de espera se envió", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ApiException) {
            }
        }
    }

    companion object {
        fun factory(): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as UniEatApplication
                WaitMeasurementViewModel(app.container.reportRepository, app.container.arrivalCache)
            }
        }
    }

}
