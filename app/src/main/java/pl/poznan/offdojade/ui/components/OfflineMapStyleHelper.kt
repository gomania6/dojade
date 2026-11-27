package pl.poznan.offdojade.ui.components

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.io.File

/**
 * Общий генератор стиля оффлайн-карты `poznan.pmtiles` (Protomaps v4) для светлой и тёмной темы,
 * с подписями улиц, районов и значками главных мест/университетов Познани при сильном приближении.
 */
object OfflineMapStyleHelper {

    private const val SOURCE_POZNAN_POIS = "offdojade-poznan-pois-source"
    private const val LAYER_POZNAN_POIS = "offdojade-poznan-pois-layer"

    private data class PoznanLandmark(
        val id: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val badgeLetter: String,
        val colorHex: String
    )

    private val LANDMARKS = listOf(
        PoznanLandmark("uam", "UAM Collegium Minus", 52.40847, 16.91534, "U", "#4B83C3"),
        PoznanLandmark("pp", "Politechnika Poznańska", 52.40360, 16.94980, "U", "#4B83C3"),
        PoznanLandmark("upp", "Uniwersytet Przyrodniczy", 52.42610, 16.90490, "U", "#4B83C3"),
        PoznanLandmark("uep", "Uniwersytet Ekonomiczny", 52.40420, 16.91610, "U", "#4B83C3"),
        PoznanLandmark("ump", "Uniwersytet Medyczny", 52.40910, 16.91680, "U", "#4B83C3"),
        PoznanLandmark("morasko", "Kampus UAM Morasko", 52.46710, 16.92550, "U", "#4B83C3"),
        PoznanLandmark("rynek", "Stary Rynek", 52.40820, 16.93360, "★", "#D98A4E"),
        PoznanLandmark("zamek", "CK Zamek", 52.40765, 16.91885, "★", "#D98A4E"),
        PoznanLandmark("katedra", "Ostrów Tumski", 52.41170, 16.94880, "★", "#D98A4E"),
        PoznanLandmark("cytadela", "Park Cytadela", 52.42160, 16.93470, "♣", "#3FA372"),
        PoznanLandmark("malta", "Jezioro Maltańskie", 52.40270, 16.97650, "♣", "#3FA372"),
        PoznanLandmark("palmiarnia", "Palmiarnia", 52.39870, 16.90060, "♣", "#3FA372"),
        PoznanLandmark("stadion", "Enea Stadion", 52.39760, 16.85800, "⚽", "#3FA372"),
        PoznanLandmark("mtp", "MTP Targi", 52.40410, 16.90820, "★", "#9B72CF"),
        PoznanLandmark("pkp", "Poznań Główny", 52.40180, 16.91220, "P", "#47A8B8"),
        PoznanLandmark("browar", "Stary Browar", 52.40140, 16.92770, "S", "#9B72CF"),
        PoznanLandmark("posnania", "Posnania", 52.39690, 16.95570, "S", "#9B72CF"),
        PoznanLandmark("avenida", "Avenida", 52.40130, 16.91420, "S", "#9B72CF")
    )

    fun buildOfflineMapStyleJson(localMapFile: File?, isDarkTheme: Boolean): String {
        val bg = if (isDarkTheme) "#0D1522" else "#EEF1F5"
        val earth = if (isDarkTheme) "#111A2B" else "#F4F6F8"
        val park = if (isDarkTheme) "#132B24" else "#D5EAD0"
        val water = if (isDarkTheme) "#173F6B" else "#B5DAF5"
        val buildingFill = if (isDarkTheme) "#1C293D" else "#E2E6EC"
        val buildingOutline = if (isDarkTheme) "#273750" else "#CFD6E0"
        val rail = if (isDarkTheme) "#283548" else "#B8C1CE"
        val path = if (isDarkTheme) "#2B3A50" else "#CBD2DC"
        val minorCasing = if (isDarkTheme) "#0A101B" else "#D2D8E1"
        val minorFill = if (isDarkTheme) "#33445E" else "#FFFFFF"
        val majorCasing = if (isDarkTheme) "#090F1A" else "#C2CAD6"
        val majorFill = if (isDarkTheme) "#526787" else "#FFFFFF"
        val highwayCasing = if (isDarkTheme) "#090F1A" else "#D4A857"
        val highwayFill = if (isDarkTheme) "#6E85A8" else "#FCE39D"
        val roadLabelText = if (isDarkTheme) "#CBD5E1" else "#334155"
        val roadLabelHalo = if (isDarkTheme) "#0D1522" else "#FFFFFF"
        val placeLabelText = if (isDarkTheme) "#94A3B8" else "#475569"

        if (localMapFile != null && localMapFile.exists()) {
            val uri = "pmtiles://file://${localMapFile.absolutePath}"
            return """
                {
                  "version": 8,
                  "name": "Poznan Offline ${if (isDarkTheme) "Dark" else "Light"}",
                  "glyphs": "https://protomaps.github.io/basemaps-assets/fonts/{fontstack}/{range}.pbf",
                  "sources": {
                    "poznan": {
                      "type": "vector",
                      "url": "$uri"
                    }
                  },
                  "layers": [
                    {
                      "id": "background",
                      "type": "background",
                      "paint": { "background-color": "$bg" }
                    },
                    {
                      "id": "pm-earth",
                      "type": "fill",
                      "source": "poznan",
                      "source-layer": "earth",
                      "paint": { "fill-color": "$earth" }
                    },
                    {
                      "id": "pm-parks",
                      "type": "fill",
                      "source": "poznan",
                      "source-layer": "landuse",
                      "filter": ["in", "kind", "park", "forest", "wood", "grass", "meadow", "cemetery", "recreation_ground", "garden", "nature_reserve", "scrub"],
                      "paint": { "fill-color": "$park", "fill-opacity": 0.88 }
                    },
                    {
                      "id": "pm-water",
                      "type": "fill",
                      "source": "poznan",
                      "source-layer": "water",
                      "filter": ["==", ["geometry-type"], "Polygon"],
                      "paint": { "fill-color": "$water" }
                    },
                    {
                      "id": "pm-water-lines",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "water",
                      "filter": ["==", ["geometry-type"], "LineString"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$water",
                        "line-width": ["interpolate", ["linear"], ["zoom"], 10, 1.0, 14, 2.5, 16, 4.5]
                      }
                    },
                    {
                      "id": "pm-buildings",
                      "type": "fill",
                      "source": "poznan",
                      "source-layer": "buildings",
                      "minzoom": 13,
                      "paint": {
                        "fill-color": "$buildingFill",
                        "fill-outline-color": "$buildingOutline",
                        "fill-opacity": ["interpolate", ["linear"], ["zoom"], 13, 0.35, 15, 0.88]
                      }
                    },
                    {
                      "id": "pm-rail",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 12,
                      "filter": ["==", "kind", "rail"],
                      "paint": {
                        "line-color": "$rail",
                        "line-width": ["interpolate", ["linear"], ["zoom"], 12, 0.8, 15, 1.6],
                        "line-opacity": 0.65
                      }
                    },
                    {
                      "id": "pm-paths",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 15,
                      "filter": ["in", "kind", "path", "other"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$path",
                        "line-width": ["interpolate", ["linear"], ["zoom"], 15, 1.0, 17, 2.2],
                        "line-dasharray": [2, 2],
                        "line-opacity": 0.6
                      }
                    },
                    {
                      "id": "pm-minor-casing",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 12,
                      "filter": ["==", "kind", "minor_road"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$minorCasing",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.8, 14, 4.8, 16, 11.0]
                      }
                    },
                    {
                      "id": "pm-minor-fill",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 12,
                      "filter": ["==", "kind", "minor_road"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$minorFill",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 12, 1.0, 14, 3.2, 16, 8.2]
                      }
                    },
                    {
                      "id": "pm-major-casing",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 9,
                      "filter": ["==", "kind", "major_road"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$majorCasing",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 2.6, 13, 6.2, 16, 15.0]
                      }
                    },
                    {
                      "id": "pm-major-fill",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 9,
                      "filter": ["==", "kind", "major_road"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$majorFill",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 10, 1.5, 13, 4.4, 16, 12.0]
                      }
                    },
                    {
                      "id": "pm-highway-casing",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 7,
                      "filter": ["==", "kind", "highway"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$highwayCasing",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 3.2, 13, 8.0, 16, 18.0]
                      }
                    },
                    {
                      "id": "pm-highway-fill",
                      "type": "line",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 7,
                      "filter": ["==", "kind", "highway"],
                      "layout": { "line-cap": "round", "line-join": "round" },
                      "paint": {
                        "line-color": "$highwayFill",
                        "line-width": ["interpolate", ["exponential", 1.5], ["zoom"], 9, 2.0, 13, 6.0, 16, 14.5]
                      }
                    },
                    {
                      "id": "pm-road-labels",
                      "type": "symbol",
                      "source": "poznan",
                      "source-layer": "roads",
                      "minzoom": 13,
                      "filter": ["has", "name"],
                      "layout": {
                        "symbol-placement": "line",
                        "text-field": ["get", "name"],
                        "text-font": ["Noto Sans Regular"],
                        "text-size": ["interpolate", ["linear"], ["zoom"], 13, 10, 16, 13],
                        "text-max-angle": 30
                      },
                      "paint": {
                        "text-color": "$roadLabelText",
                        "text-halo-color": "$roadLabelHalo",
                        "text-halo-width": 1.4
                      }
                    },
                    {
                      "id": "pm-place-labels",
                      "type": "symbol",
                      "source": "poznan",
                      "source-layer": "places",
                      "minzoom": 10,
                      "filter": ["has", "name"],
                      "layout": {
                        "text-field": ["get", "name"],
                        "text-font": ["Noto Sans Medium"],
                        "text-size": ["interpolate", ["linear"], ["zoom"], 10, 10, 14, 13]
                      },
                      "paint": {
                        "text-color": "$placeLabelText",
                        "text-halo-color": "$roadLabelHalo",
                        "text-halo-width": 1.2
                      }
                    }
                  ]
                }
            """.trimIndent()
        }

        return """
            {
              "version": 8,
              "name": "OffDojade Fallback Style",
              "sources": {},
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "$bg" }
                }
              ]
            }
        """.trimIndent()
    }

    /**
     * Добавляет на карту слой значков главных мест Познани (университеты, достопримечательности,
     * вокзал, парки, ТЦ) в стиле Google Maps. Отображается только при приближении (zoom >= 13.5).
     */
    fun addPoznanLandmarksLayer(style: Style, isDarkTheme: Boolean) {
        if (style.getSource(SOURCE_POZNAN_POIS) != null) return

        val features = LANDMARKS.map { lm ->
            val iconId = "poi_${lm.id}_${if (isDarkTheme) "d" else "l"}"
            if (style.getImage(iconId) == null) {
                style.addImage(iconId, createLandmarkBitmap(lm.name, lm.badgeLetter, lm.colorHex, isDarkTheme))
            }
            Feature.fromGeometry(Point.fromLngLat(lm.lon, lm.lat)).apply {
                addStringProperty("icon", iconId)
            }
        }
        style.addSource(GeoJsonSource(SOURCE_POZNAN_POIS, FeatureCollection.fromFeatures(features)))
        style.addLayer(
            SymbolLayer(LAYER_POZNAN_POIS, SOURCE_POZNAN_POIS)
                .withProperties(
                    PropertyFactory.iconImage(Expression.get("icon")),
                    PropertyFactory.iconAllowOverlap(false),
                    PropertyFactory.iconIgnorePlacement(false),
                    PropertyFactory.iconAnchor("left"),
                    PropertyFactory.iconOffset(arrayOf(-20f, 0f))
                ).apply { minZoom = 13.5f }
        )
    }

    private fun createLandmarkBitmap(
        label: String,
        letter: String,
        colorHex: String,
        isDarkTheme: Boolean
    ): Bitmap {
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (isDarkTheme) Color.parseColor("#E2E8F0") else Color.parseColor("#1E293B")
            textSize = 24f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val haloPaint = Paint(textPaint).apply {
            color = if (isDarkTheme) Color.parseColor("#0D1522") else Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 5f
        }
        val textWidth = textPaint.measureText(label)
        val circleSize = 36f
        val width = (circleSize + 10f + textWidth + 10f).toInt().coerceAtLeast(64)
        val height = 44

        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val cy = height / 2f
        val cx = circleSize / 2f + 2f

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor(colorHex)
            style = Paint.Style.FILL
        }
        val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(cx, cy, circleSize / 2f - 2f, bgPaint)
        canvas.drawCircle(cx, cy, circleSize / 2f - 2f, borderPaint)

        val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 20f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        canvas.drawText(letter, cx, cy + 7f, letterPaint)

        val textX = circleSize + 10f
        val textY = cy + 8f
        canvas.drawText(label, textX, textY, haloPaint)
        canvas.drawText(label, textX, textY, textPaint)
        return bmp
    }
}
