package com.novaplay.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.util.concurrent.Executors

fun fmt(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
}

fun sizeText(b: Long): String =
    if (b >= 1073741824L) "%.2f GB".format(b / 1073741824.0) else "${b / 1048576} MB"

data class Video(val uri: String, val name: String, val folder: String, val duration: Long, val size: Long)
data class Row(val title: String, val sub: String, val thumb: String?)
class Holder(val img: ImageView, val title: TextView, val sub: TextView)

class MainActivity : Activity() {

    private val BG = 0xFF0B0F19.toInt()
    private val BAR = 0xFF111827.toInt()
    private val ORANGE = 0xFFFF8A00.toInt()

    private lateinit var listView: ListView
    private lateinit var titleView: TextView
    private val all = ArrayList<Video>()
    private var currentFolder: String? = null
    private val cache = LruCache<String, Bitmap>(80)
    private val pool = Executors.newFixedThreadPool(3)
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BAR

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)

        titleView = TextView(this)
        titleView.text = "NovaPlay"
        titleView.textSize = 22f
        titleView.setTypeface(null, Typeface.BOLD)
        titleView.setTextColor(ORANGE)
        titleView.setPadding(dp(16), dp(18), dp(16), dp(14))
        titleView.setBackgroundColor(BAR)
        root.addView(titleView, LinearLayout.LayoutParams(-1, -2))

        listView = ListView(this)
        listView.divider = ColorDrawable(0x22FFFFFF)
        listView.dividerHeight = 1
        root.addView(listView, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)

        val perm = if (Build.VERSION.SDK_INT >= 33)
            Manifest.permission.READ_MEDIA_VIDEO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE

        if (checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED) loadVideos()
        else requestPermissions(arrayOf(perm), 1)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) loadVideos()
    }

    private fun loadVideos() {
        all.clear()
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        contentResolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val folderCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            while (c.moveToNext()) {
                val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                all.add(
                    Video(
                        uri.toString(),
                        c.getString(nameCol) ?: "video",
                        c.getString(folderCol) ?: "Unknown",
                        c.getLong(durCol),
                        c.getLong(sizeCol)
                    )
                )
            }
        }
        showFolders()
    }

    private fun showFolders() {
        currentFolder = null
        titleView.text = "NovaPlay"
        val groups = all.groupBy { it.folder }.toSortedMap(String.CASE_INSENSITIVE_ORDER)
        val names = groups.keys.toList()
        val rows = names.map { n ->
            val vids = groups[n]!!
            Row(n, "${vids.size} videos", vids.first().uri)
        }
        listView.adapter = RowAdapter(rows)
        listView.setOnItemClickListener { _, _, pos, _ -> showVideos(names[pos]) }
    }

    private fun showVideos(folder: String) {
        currentFolder = folder
        titleView.text = folder
        val vids = all.filter { it.folder == folder }.sortedBy { it.name.lowercase() }
        val rows = vids.map { Row(it.name, "${fmt(it.duration)}  •  ${sizeText(it.size)}", it.uri) }
        listView.adapter = RowAdapter(rows)
        listView.setOnItemClickListener { _, _, pos, _ ->
            val i = Intent(this, PlayerActivity::class.java)
            i.putStringArrayListExtra("uris", ArrayList(vids.map { it.uri }))
            i.putExtra("index", pos)
            startActivity(i)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (currentFolder != null) showFolders() else super.onBackPressed()
    }

    private fun loadThumb(uriStr: String): Bitmap? {
        return try {
            val uri = Uri.parse(uriStr)
            if (Build.VERSION.SDK_INT >= 29) {
                contentResolver.loadThumbnail(uri, Size(320, 180), null)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Video.Thumbnails.getThumbnail(
                    contentResolver, ContentUris.parseId(uri),
                    MediaStore.Video.Thumbnails.MINI_KIND, null
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun makeRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.setPadding(dp(12), dp(10), dp(12), dp(10))

        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        img.setBackgroundColor(0xFF1F2937.toInt())
        row.addView(img, LinearLayout.LayoutParams(dp(120), dp(68)))

        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), 0, 0, 0)

        val t = TextView(this)
        t.setTextColor(Color.WHITE)
        t.textSize = 15f
        t.maxLines = 2
        t.ellipsize = TextUtils.TruncateAt.END

        val s = TextView(this)
        s.setTextColor(0xFF9CA3AF.toInt())
        s.textSize = 12f
        s.setPadding(0, dp(4), 0, 0)

        col.addView(t)
        col.addView(s)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.tag = Holder(img, t, s)
        return row
    }

    inner class RowAdapter(private val rows: List<Row>) : BaseAdapter() {
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val v = convertView ?: makeRow()
            val h = v.tag as Holder
            val r = rows[position]
            h.title.text = r.title
            h.sub.text = r.sub
            h.img.tag = r.thumb
            h.img.setImageBitmap(null)
            val key = r.thumb
            if (key != null) {
                val cached = cache.get(key)
                if (cached != null) {
                    h.img.setImageBitmap(cached)
                } else {
                    pool.execute {
                        val bmp = loadThumb(key)
                        if (bmp != null) {
                            cache.put(key, bmp)
                            ui.post { if (h.img.tag == key) h.img.setImageBitmap(bmp) }
                        }
                    }
                }
            }
            return v
        }
    }
}
