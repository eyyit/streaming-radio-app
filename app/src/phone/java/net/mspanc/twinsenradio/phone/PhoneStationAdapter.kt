package net.mspanc.twinsenradio.phone

import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import androidx.recyclerview.widget.RecyclerView
import net.mspanc.twinsenradio.R
import net.mspanc.twinsenradio.data.Prefs
import net.mspanc.twinsenradio.data.Station
import net.mspanc.twinsenradio.data.StationRepository
import net.mspanc.twinsenradio.playback.MetadataFactory
import net.mspanc.twinsenradio.playback.PlaybackStatusBus
import net.mspanc.twinsenradio.ui.ArtworkLoader

@UnstableApi
internal class PhoneStationAdapter(
    private val owner: LifecycleOwner,
    private val prefs: Prefs,
    private val repo: StationRepository,
    private val play: (Station) -> Unit
) : RecyclerView.Adapter<PhoneStationAdapter.Holder>() {
    private var stations = emptyList<Station>()
    private var signature = ""
    fun update(values: List<Station>) {
        val key = values.toString() + prefs.favourites + PlaybackStatusBus.stationId.value +
            PlaybackStatusBus.nowPlaying.value + owner.lifecycle.currentState +
            net.mspanc.twinsenradio.playback.StationArtworkStore.loading.value + prefs.onlineArtworkEnabled
        stations = values
        if (key != signature) { signature = key; notifyDataSetChanged() }
    }
    override fun getItemCount() = stations.size
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val wrapper = PhoneUi.column(c)
        val row = PhoneUi.row(c).apply { minimumHeight = PhoneUi.dp(c, 56) }
        val selected = View(c)
        row.addView(selected, LinearLayout.LayoutParams(PhoneUi.dp(c, 3), PhoneUi.dp(c, 48)))
        val art = FrameLayout(c).apply { background = PhoneUi.shape(c, PhoneUi.SURFACE, 8); clipToOutline = true }
        val initials = PhoneUi.text(c, size = 18f).apply { gravity = Gravity.CENTER }
        val logo = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        art.addView(initials, FrameLayout.LayoutParams(-1, -1)); art.addView(logo, FrameLayout.LayoutParams(-1, -1))
        row.addView(art, LinearLayout.LayoutParams(PhoneUi.dp(c, 40), PhoneUi.dp(c, 40)).apply {
            marginStart = PhoneUi.dp(c, 10); marginEnd = PhoneUi.dp(c, 12)
        })
        val text = PhoneUi.column(c)
        val title = PhoneUi.text(c, size = 18f).apply {
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setTypeface(typeface, Typeface.BOLD)
        }
        val subtitle = PhoneUi.text(c, size = 14f, muted = true).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        text.addView(title); text.addView(subtitle)
        row.addView(text, LinearLayout.LayoutParams(0, -2, 1f))
        val heart = PhoneUi.icon(c, R.drawable.ic_star_outline, "Favorite station").apply {
            setPadding(PhoneUi.dp(c, 8), PhoneUi.dp(c, 8), PhoneUi.dp(c, 8), PhoneUi.dp(c, 8))
        }
        row.addView(heart)
        row.isClickable = true; row.isFocusable = true
        wrapper.addView(row); wrapper.addView(PhoneUi.divider(c))
        return Holder(wrapper, row, selected, title, subtitle, initials, logo, heart)
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        val station = stations[position]; val playing = station.id == PlaybackStatusBus.stationId.value
        holder.row.tag = "phone_station_${station.id}"
        holder.title.text = station.name
        holder.initials.text = station.name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.take(1) }
        val now = PlaybackStatusBus.nowPlaying.value
        holder.subtitle.text = when {
            repo.isDetachedFavorite(station.id) -> "Not in current playlists"
            playing && !now?.songTitle.isNullOrBlank() -> listOfNotNull(now?.songTitle, now?.artist).joinToString(" — ")
            else -> station.genre
        }
        holder.selected.setBackgroundColor(if (playing) net.mspanc.twinsenradio.data.AppearanceTheme.current(holder.row.context).accent else PhoneUi.CANVAS)
        val favorite = station.id in prefs.favourites
        holder.heart.setImageResource(if (favorite) R.drawable.ic_star_filled else R.drawable.ic_star_outline)
        holder.heart.isSelected = favorite
        holder.heart.contentDescription = if (favorite) "Remove ${station.name} from Favorites" else "Add ${station.name} to Favorites"
        holder.heart.setOnClickListener { prefs.toggleFavourite(station.id) }
        holder.row.setOnClickListener { play(station) }
        val factory = MetadataFactory(holder.row.context, prefs)
        val uri = factory.logoDisplayUri(station)
        val fallback = factory.logoResId(station)
        val showInitials = uri == null && fallback == R.drawable.logo_placeholder
        val logoKey = uri?.toString() ?: if (showInitials) null else "res:$fallback"
        if (holder.boundStationId != station.id || holder.boundLogoKey != logoKey) {
            // Clear the old bitmap and invalidate its request before the new
            // image starts, even while the owner is below STARTED.
            ArtworkLoader.into(
                owner.lifecycleScope, null, null, android.R.color.transparent, holder.logo
            )
            holder.boundStationId = station.id
            holder.boundLogoKey = logoKey
        }
        holder.initials.visibility = if (showInitials) View.VISIBLE else View.GONE
        holder.logo.visibility = if (uri != null || !showInitials) View.VISIBLE else View.GONE
        if (uri != null) ArtworkLoader.into(
            owner.lifecycleScope, null, uri,
            if (showInitials) android.R.color.transparent else fallback,
            holder.logo,
            fallbackDrawable = if (fallback == R.drawable.logo_placeholder) PhoneStationArtwork(station.name) else null
        ) else if (!showInitials) holder.logo.setImageResource(fallback)
    }

    override fun onViewRecycled(holder: Holder) {
        // Invalidate any in-flight load before this ImageView is reused.
        ArtworkLoader.into(owner.lifecycleScope, null, null, android.R.color.transparent, holder.logo)
        holder.boundStationId = null
        holder.boundLogoKey = null
        holder.initials.visibility = View.GONE
        super.onViewRecycled(holder)
    }
    class Holder(root: View, val row: View, val selected: View, val title: TextView, val subtitle: TextView,
        val initials: TextView, val logo: ImageView, val heart: ImageButton) : RecyclerView.ViewHolder(root) {
        var boundStationId: String? = null
        var boundLogoKey: String? = null
    }
}
