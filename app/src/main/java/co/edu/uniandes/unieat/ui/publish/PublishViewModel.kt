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

/** One dish of the form. [key] only tells the drafts apart while the list changes. */
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

    /** Diet is sent only when the restaurant confirmed it; vegan also counts as vegetarian. */
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
    /** Same rules as iOS and api-v1: up to 24 h of validity, one payment method, every dish with name and price. */
    fun isValid(now: Instant): Boolean =
        establishmentId != null &&
            establishmentName.isNotBlank() &&
            title.isNotBlank() &&
            validUntil > now && validUntil <= now + PublishViewModel.MAX_VALIDITY &&
            paymentMethods.isNotEmpty() &&
            dishes.isNotEmpty() && dishes.all { it.isComplete }

    /** Something the owner typed: only then is the form worth keeping as a draft. */
    val hasInput: Boolean
        get() = dishes.any { it.name.isNotBlank() || it.description.isNotBlank() || it.priceText.isNotBlank() }
}

/** What is saved on the phone while the form is not published: the form and the version being edited, if any. */
@Serializable
data class PublishDraft(val form: PublishForm, val editing: DailyMenu? = null)

/** The lists of the tab, saved after each successful load so they can be shown offline. */
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
    /** No validated connection: publishing and closing wait for the signal (network only). */
    val offline: Boolean = false,
    /** Set when the lists come from the copy saved at that time instead of the server. */
    val savedAt: Instant? = null,
) {
    /** Only establishments an admin already approved can publish. */
    val approved: List<OwnEstablishment> get() = establishments.filter { it.approved }
}

/** "Publicar" tab for restaurants: create, edit (new version) and close the menu of the day. */
@OptIn(FlowPreview::class)
class PublishViewModel(
    private val menus: MenuRepository,
    private val establishments: EstablishmentRepository,
    private val clock: () -> Instant = Instant::now,
    /** Saved lists and the unpublished draft. null in demo mode. */
    private val store: OfflineStore? = null,
    /** Validated-connection changes: buttons turn off without signal and everything reloads when it returns. */
    online: Flow<Boolean> = emptyFlow(),
) : ViewModel() {
    private var nextDishKey = 0L

    private val _state = MutableStateFlow(PublishUiState(form = emptyForm()))
    val state: StateFlow<PublishUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // The draft first, so the load does not overwrite the form with the default establishment.
            restoreDraft()
            refresh()
        }
        viewModelScope.launch {
            // Every change to the form is written to disk half a second after the owner stops typing.
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
                // Back online: replace the saved copy (or the "no data" message) with the server's.
                if (isOnline && (current.offline || current.savedAt != null)) refresh()
            }
        }
    }

    fun load() {
        viewModelScope.launch { refresh() }
    }

    /** Network falling back to cache: the server's lists, or the last ones saved when there is no signal. */
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
            // The first approved establishment is selected so the form starts filled in.
            val first = withData.approved.firstOrNull()
            if (withData.form.establishmentId == null && first != null) {
                withData.copy(form = withData.form.filledFrom(first))
            } else {
                withData
            }
        }
    }

    /** Case study 2 of the course (Luisa): a form filled in must survive the app closing. */
    private suspend fun restoreDraft() {
        val draft = store?.load(DRAFT_KEY, PublishDraft.serializer())?.value ?: return
        val now = clock()
        // An old draft can come back expired: it gets the default validity so it can still be published.
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
                    // An expired menu gets at least one more hour, so saving it is possible.
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
                // Published: the draft must not come back the next time the tab opens.
                store?.remove(DRAFT_KEY)
                // Already published: if the list fails to reload now, the old one stays instead of an error.
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
                    // Someone else edited first: reload so the owner edits the current version.
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
                    // Network only: a publication is never queued. It creates a version, and sent hours later it
                    // could overwrite a newer one (case study 1 of the course, Dairo).
                    if (e.code == ApiException.OFFLINE) offlineAction(PUBLISH_OFFLINE_MESSAGE)
                    else fail(e, "No se pudo guardar el menú. Revisa los datos e inténtalo de nuevo.")
                }
            }
        }
    }

    /** Takes the menu out of the feed before it expires; its history stays. */
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

    /** GET /menus/mine answers FORBIDDEN while no establishment is approved: that just means no menus yet. */
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

/** "vigente", "vencida" or "cerrada", as in the owner's list on iOS. */
fun DailyMenu.ownerStatus(now: Instant): String = when {
    closedAt != null -> "cerrada"
    isActive(now) -> "vigente"
    else -> "vencida"
}
