package co.edu.uniandes.unieat.ui.publish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import co.edu.uniandes.unieat.core.model.DailyMenu
import co.edu.uniandes.unieat.core.model.InstantSerializer
import co.edu.uniandes.unieat.core.model.MenuBody
import co.edu.uniandes.unieat.core.model.MenuDish
import co.edu.uniandes.unieat.core.model.OwnEstablishment
import co.edu.uniandes.unieat.data.local.OfflineStore
import co.edu.uniandes.unieat.data.remote.ApiException
import co.edu.uniandes.unieat.data.repository.EstablishmentRepository
import co.edu.uniandes.unieat.data.repository.MenuRepository
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.time.Duration
import java.time.Instant

/** One dish in the form. key is only for the list. */
@Serializable
data class DishDraft(
    val key: Long,
    val name: String = "",
    val description: String = "",
    val priceText: String = "",
    val vegetarian: Boolean = false,
    val vegan: Boolean = false,
    val dietaryKnown: Boolean = false,
) {
    val price: Int get() = priceText.toIntOrNull() ?: 0
    val isComplete: Boolean get() = name.isNotBlank() && price > 0

    /** Diet only if confirmed. Vegan also counts as vegetarian. */
    fun toBody(): MenuBody.Dish {
        val tags = when {
            !dietaryKnown -> emptyList()
            vegan -> listOf("vegetarian", "vegan")
            vegetarian -> listOf("vegetarian")
            else -> emptyList()
        }
        return MenuBody.Dish(
            name = name.trim(),
            description = description.trim(),
            priceCop = price,
            dietaryKnown = dietaryKnown,
            dietaryTags = tags,
        )
    }

    companion object {
        fun from(dish: MenuDish, key: Long) = DishDraft(
            key = key,
            name = dish.name,
            description = dish.description,
            priceText = dish.priceCop.toString(),
            vegetarian = "vegetarian" in dish.dietaryTags,
            vegan = "vegan" in dish.dietaryTags,
            dietaryKnown = dish.dietaryKnown,
        )
    }
}

@Serializable
data class PublishForm(
    val establishmentId: String? = null,
    val establishmentName: String = "",
    val area: String = "Centro",
    val address: String = "",
    val entranceDescription: String = "",
    val title: String = "Almuerzo completo",
    @Serializable(InstantSerializer::class) val validUntil: Instant,
    val paymentMethods: Set<String> = setOf("Nequi", "Efectivo"),
    val dishes: List<DishDraft>,
) {
    /** Same rules as iOS and api-v1: max 24 h, one payment method, every dish with name and price. */
    fun isValid(now: Instant): Boolean =
        establishmentId != null &&
            establishmentName.isNotBlank() &&
            title.isNotBlank() &&
            validUntil > now && validUntil <= now + PublishViewModel.MAX_VALIDITY &&
            paymentMethods.isNotEmpty() &&
            dishes.isNotEmpty() && dishes.all { it.isComplete }

    /** What's missing to publish (empty = valid). */
    fun missing(now: Instant): List<String> = buildList {
        if (establishmentId == null) add("elegir un establecimiento aprobado")
        if (establishmentName.isBlank()) add("el nombre del restaurante")
        if (title.isBlank()) add("el nombre del menú")
        if (validUntil <= now) add("una hora de vigencia futura")
        if (validUntil > now + PublishViewModel.MAX_VALIDITY) add("una vigencia de máximo 24 horas")
        if (paymentMethods.isEmpty()) add("al menos un medio de pago")
        dishes.forEachIndexed { index, dish ->
            val n = if (dishes.size > 1) " del plato ${index + 1}" else " del plato"
            if (dish.name.isBlank()) add("el nombre$n")
            if (dish.price <= 0) add("el precio$n")
        }
    }

    /** Only worth a draft if the owner typed something. */
    val hasInput: Boolean
        get() = dishes.any { it.name.isNotBlank() || it.description.isNotBlank() || it.priceText.isNotBlank() }
}

/** Draft on the phone: the form and the version being edited. */
@Serializable
data class PublishDraft(val form: PublishForm, val editing: DailyMenu? = null)

/** Lists saved after each load, for offline. */
@Serializable
data class SavedPublishData(val establishments: List<OwnEstablishment>, val myMenus: List<DailyMenu>)

data class PublishUiState(
    val loading: Boolean = true,
    val establishments: List<OwnEstablishment> = emptyList(),
    val form: PublishForm,
    val editing: DailyMenu? = null,
    val myMenus: List<DailyMenu> = emptyList(),
    val message: String? = null,
    val saving: Boolean = false,
    val sessionExpired: Boolean = false,
    /** No connection: publish and close are off (network only). */
    val offline: Boolean = false,
    /** Time of the saved copy when offline. */
    val savedAt: Instant? = null,
) {
    /** Only approved establishments can publish. */
    val approved: List<OwnEstablishment> get() = establishments.filter { it.approved }
}

/** Publicar tab: create, edit (new version) and close today's menu. */
@OptIn(FlowPreview::class)
class PublishViewModel(
    private val menus: MenuRepository,
    private val establishments: EstablishmentRepository,
    private val clock: () -> Instant = Instant::now,
    /** Saved lists + draft. null in demo mode. */
    private val store: OfflineStore? = null,
    /** Offline turns the buttons off; everything reloads when the signal returns. */
    online: Flow<Boolean> = emptyFlow(),
) : ViewModel() {
    private var nextDishKey = 0L

    private val _state = MutableStateFlow(PublishUiState(form = emptyForm()))
    val state: StateFlow<PublishUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // draft first, so the default establishment doesn't overwrite the form
            restoreDraft()
            refresh()
        }
        viewModelScope.launch {
            // save the draft 500 ms after the owner stops typing
            _state.map { PublishDraft(it.form, it.editing) }
                .distinctUntilChanged()
                .drop(1)
                .debounce(DRAFT_DELAY_MILLIS)
                .collect { saveDraft(it) }
        }
        viewModelScope.launch {
            online.collect { isOnline ->
                val current = _state.value
                _state.update { it.copy(offline = !isOnline) }
                // back online: replace the saved copy with the server's
                if (isOnline && (current.offline || current.savedAt != null)) refresh()
            }
        }
    }

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** Network falling back to cache. */
    private suspend fun refresh() {
        _state.update { it.copy(loading = true) }
        try {
            val mine = establishments.mine()
            val own = ownMenus()
            store?.save(PUBLISH_DATA_KEY, SavedPublishData.serializer(), SavedPublishData(mine, own))
            showData(mine, own, savedAt = null)
        } catch (e: ApiException) {
            if (e.code != ApiException.OFFLINE) return fail(e, "No se pudieron cargar tus establecimientos.")
            val saved = store?.load(PUBLISH_DATA_KEY, SavedPublishData.serializer())
            if (saved != null) {
                showData(saved.value.establishments, saved.value.myMenus, saved.savedAt)
                _state.update { it.copy(offline = true) }
            } else {
                _state.update { it.copy(loading = false, offline = true, message = NO_SAVED_DATA_MESSAGE) }
            }
        }
    }

    private fun showData(mine: List<OwnEstablishment>, own: List<DailyMenu>, savedAt: Instant?) {
        _state.update { state ->
            val withData = state.copy(loading = false, establishments = mine, myMenus = own, savedAt = savedAt)
            // start with the first approved establishment
            val first = withData.approved.firstOrNull()
            if (withData.form.establishmentId == null && first != null) {
                withData.copy(form = withData.form.filledFrom(first))
            } else {
                withData
            }
        }
    }

    /** The draft survives closing the app (Luisa case). */
    private suspend fun restoreDraft() {
        val draft = store?.load(DRAFT_KEY, PublishDraft.serializer())?.value ?: return
        val now = clock()
        // an old draft may be expired, give it the default validity
        val validUntil = draft.form.validUntil.takeIf { it > now } ?: (now + DEFAULT_VALIDITY)
        nextDishKey = (draft.form.dishes.maxOfOrNull { it.key } ?: -1) + 1
        _state.update {
            it.copy(
                form = draft.form.copy(validUntil = validUntil),
                editing = draft.editing,
                message = "Recuperamos el borrador que no alcanzaste a publicar.",
            )
        }
    }

    private suspend fun saveDraft(draft: PublishDraft) {
        val store = store ?: return
        if (draft.form.hasInput || draft.editing != null) {
            store.save(DRAFT_KEY, PublishDraft.serializer(), draft)
        } else {
            store.remove(DRAFT_KEY)
        }
    }

    fun selectEstablishment(id: String) {
        _state.update { state ->
            val establishment = state.approved.firstOrNull { it.id == id }
            if (state.editing != null || establishment == null) state else state.copy(form = state.form.filledFrom(establishment))
        }
    }

    fun updateForm(change: (PublishForm) -> PublishForm) {
        _state.update { it.copy(form = change(it.form)) }
    }

    fun togglePayment(method: String) = updateForm { form ->
        form.copy(paymentMethods = if (method in form.paymentMethods) form.paymentMethods - method else form.paymentMethods + method)
    }

    fun updateDish(key: Long, change: (DishDraft) -> DishDraft) = updateForm { form ->
        form.copy(dishes = form.dishes.map { if (it.key == key) change(it) else it })
    }

    fun addDish() = updateForm { form ->
        if (form.dishes.size >= MAX_DISHES) form else form.copy(dishes = form.dishes + newDish())
    }

    fun removeDish(key: Long) = updateForm { form ->
        if (form.dishes.size <= 1) form else form.copy(dishes = form.dishes.filterNot { it.key == key })
    }

    fun edit(menu: DailyMenu) {
        val now = clock()
        _state.update {
            it.copy(
                editing = menu,
                form = PublishForm(
                    establishmentId = menu.establishmentId,
                    establishmentName = menu.establishmentName,
                    area = menu.area,
                    address = menu.address,
                    entranceDescription = menu.entranceDescription,
                    title = menu.title,
                    // expired menu: at least one more hour so it can be saved
                    validUntil = maxOf(menu.validUntil, now + Duration.ofHours(1)),
                    paymentMethods = menu.paymentMethods.toSet(),
                    dishes = menu.items.map { dish -> DishDraft.from(dish, nextDishKey++) },
                ),
                message = "Editando versión ${menu.version}. Guardar creará una versión nueva.",
            )
        }
    }

    fun publish() {
        val current = _state.value
        if (current.offline) return offlineAction(PUBLISH_OFFLINE_MESSAGE)
        if (current.saving || !current.form.isValid(clock())) return
        val editing = current.editing
        val form = current.form
        val body = MenuBody(
            title = form.title.trim(),
            validUntil = form.validUntil,
            paymentMethods = PAYMENT_METHODS.filter { it in form.paymentMethods },
            dishes = form.dishes.map { it.toBody() },
            establishmentId = editing?.establishmentId ?: form.establishmentId,
            establishmentName = form.establishmentName.trim(),
            area = form.area,
            address = form.address.trim().ifEmpty { null },
            entranceDescription = form.entranceDescription.trim(),
            expectedVersion = editing?.version,
        )
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                if (editing != null) menus.revise(editing.id, body) else menus.publish(body)
                // published: delete the draft
                store?.remove(DRAFT_KEY)
                // already published: if the reload fails keep the old list
                val own = runCatching { ownMenus() }.getOrDefault(current.myMenus)
                _state.update {
                    it.copy(
                        saving = false,
                        editing = null,
                        myMenus = own,
                        form = it.form.copy(dishes = listOf(newDish())),
                        message = "Menú publicado. Ya es visible para los estudiantes.",
                    )
                }
            } catch (e: ApiException) {
                if (e.code == ApiException.VERSION_CONFLICT) {
                    // someone else edited first: reload the current version
                    val own = runCatching { ownMenus() }.getOrDefault(current.myMenus)
                    _state.update {
                        it.copy(
                            saving = false,
                            editing = null,
                            myMenus = own,
                            message = "Otra persona editó este menú. Recargamos la versión actual; vuelve a aplicar tus cambios.",
                        )
                    }
                } else {
                    // network only, never queued: a late publish could overwrite a newer version (Dairo case)
                    if (e.code == ApiException.OFFLINE) offlineAction(PUBLISH_OFFLINE_MESSAGE)
                    else fail(e, "No se pudo guardar el menú. Revisa los datos e inténtalo de nuevo.")
                }
            }
        }
    }

    /** Leaves edit mode or discards the draft (also deleted from disk) and starts empty. */
    fun discardDraft() {
        val wasEditing = _state.value.editing != null
        _state.update { state ->
            val empty = emptyForm()
            state.copy(
                editing = null,
                form = state.approved.firstOrNull()?.let { empty.filledFrom(it) } ?: empty,
                message = if (wasEditing) "Edición cancelada. El menú publicado no cambió." else "Borrador descartado.",
            )
        }
        viewModelScope.launch { store?.remove(DRAFT_KEY) }
    }

    /** Closes the menu early. Its history stays. */
    fun close(menu: DailyMenu) {
        if (_state.value.offline) return offlineAction(CLOSE_OFFLINE_MESSAGE)
        viewModelScope.launch {
            try {
                menus.close(menu.id, expectedVersion = null)
                val own = ownMenus()
                _state.update {
                    it.copy(
                        editing = it.editing?.takeIf { editing -> editing.id != menu.id },
                        myMenus = own,
                        message = "Publicación cerrada. El historial se conserva.",
                    )
                }
            } catch (e: ApiException) {
                if (e.code == ApiException.OFFLINE) offlineAction(CLOSE_OFFLINE_MESSAGE)
                else fail(e, "No se pudo cerrar la publicación. Inténtalo de nuevo.")
            }
        }
    }

    /** FORBIDDEN here just means no approved establishment yet, so no menus. */
    private suspend fun ownMenus(): List<DailyMenu> = try {
        menus.myMenus()
    } catch (e: ApiException) {
        if (e.code == ApiException.FORBIDDEN) emptyList() else throw e
    }

    private fun fail(e: ApiException, fallback: String) {
        _state.update {
            it.copy(
                loading = false,
                saving = false,
                sessionExpired = e.code == ApiException.AUTH_REQUIRED,
                message = e.error.message.ifBlank { fallback },
            )
        }
    }

    private fun offlineAction(message: String) {
        _state.update { it.copy(saving = false, offline = true, message = message) }
    }

    private fun PublishForm.filledFrom(establishment: OwnEstablishment) = copy(
        establishmentId = establishment.id,
        establishmentName = establishment.name,
        area = establishment.area,
        address = establishment.address,
        entranceDescription = establishment.entranceDescription,
        paymentMethods = establishment.paymentMethods.toSet(),
    )

    private fun newDish() = DishDraft(key = nextDishKey++)

    private fun emptyForm() = PublishForm(validUntil = clock() + DEFAULT_VALIDITY, dishes = listOf(newDish()))

    companion object {
        val AREAS = listOf("Centro", "Norte", "Sur", "Fuera del campus")
        val PAYMENT_METHODS = listOf("Nequi", "Daviplata", "Efectivo", "Tarjeta")
        const val MAX_DISHES = 12
        val DEFAULT_VALIDITY: Duration = Duration.ofHours(4)
        val MAX_VALIDITY: Duration = Duration.ofHours(24)
        const val DRAFT_DELAY_MILLIS = 500L
        const val PUBLISH_DATA_KEY = "publish-data"
        const val DRAFT_KEY = "publish-draft"
        const val NO_SAVED_DATA_MESSAGE =
            "Sin conexión: aún no hay una copia de tus establecimientos en este teléfono. Se cargarán solos al volver la señal."
        const val PUBLISH_OFFLINE_MESSAGE =
            "Sin conexión: publicar necesita señal. Tu borrador quedó guardado en el teléfono; publícalo cuando vuelva la señal."
        const val CLOSE_OFFLINE_MESSAGE =
            "Sin conexión: cerrar la publicación necesita señal. Inténtalo cuando vuelva la señal."
    }
}

/** "vigente", "vencida" or "cerrada", like iOS. */
fun DailyMenu.ownerStatus(now: Instant): String = when {
    closedAt != null -> "cerrada"
    isActive(now) -> "vigente"
    else -> "vencida"
}
