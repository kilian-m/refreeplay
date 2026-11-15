package io.github.kilianm.refreeplay

import org.json.JSONArray
import org.json.JSONObject

/**
 * Setting a camera phone's camera from the main phone. The camera phone tells what its cameras
 * can record and what is set now ([capabilities]); the main phone shows that as a dialog and
 * sends back a [Choice] ([choiceText]). Both travel as JSON text over the video connection.
 */
object CameraRemote {
    class Fps(val fps: Int, val label: String)
    class Mode(val width: Int, val height: Int, val fps: List<Fps>)
    class Camera(val key: String, val label: String, val modes: List<Mode>)

    /** Camera settings: which camera, picture size, frame rate, and quality 0..2 as in [Prefs.quality]. */
    data class Choice(val cameraKey: String, val width: Int, val height: Int, val fps: Int, val quality: Int)

    class Capabilities(val cameras: List<Camera>, val current: Choice)

    /** All cameras of this phone with their picture sizes and frame rates, as JSON. Takes a moment: ask once. */
    fun describeCameras(catalog: CameraCatalog): String {
        val cameras = JSONArray()
        for (cam in runCatching { catalog.cameras() }.getOrDefault(emptyList())) {
            val modes = JSONArray()
            for (size in catalog.resolutions(cam)) {
                val rates = JSONArray()
                for (f in catalog.fpsOptions(cam, size)) rates.put(JSONObject().put("fps", f.fps).put("label", f.label))
                if (rates.length() > 0) modes.put(JSONObject().put("w", size.width).put("h", size.height).put("fps", rates))
            }
            if (modes.length() > 0) cameras.put(JSONObject().put("key", cam.key).put("label", cam.label.trim()).put("modes", modes))
        }
        return cameras.toString()
    }

    /** The message for the main phone: [cameras] from [describeCameras] and what is set now. */
    fun capabilities(cameras: String, cfg: CaptureConfig, quality: Int): String =
        JSONObject()
            .put("cameras", JSONArray(cameras))
            .put("current", choiceObject(Choice(cfg.camera.key, cfg.width, cfg.height, cfg.fps, quality)))
            .toString()

    fun parseCapabilities(json: String): Capabilities? = runCatching {
        val o = JSONObject(json)
        val cams = o.getJSONArray("cameras")
        val cameras = (0 until cams.length()).map { i ->
            val c = cams.getJSONObject(i)
            val modes = c.getJSONArray("modes")
            Camera(c.getString("key"), c.getString("label"), (0 until modes.length()).map { j ->
                val m = modes.getJSONObject(j)
                val rates = m.getJSONArray("fps")
                Mode(m.getInt("w"), m.getInt("h"), (0 until rates.length()).map { k ->
                    rates.getJSONObject(k).let { Fps(it.getInt("fps"), it.getString("label")) }
                })
            })
        }
        Capabilities(cameras, parseChoice(o.getJSONObject("current")))
    }.getOrNull()

    fun choiceText(c: Choice): String = choiceObject(c).toString()

    fun parseChoice(json: String): Choice? = runCatching { parseChoice(JSONObject(json)) }.getOrNull()

    private fun choiceObject(c: Choice): JSONObject =
        JSONObject().put("camera", c.cameraKey).put("w", c.width).put("h", c.height).put("fps", c.fps).put("quality", c.quality)

    private fun parseChoice(o: JSONObject): Choice =
        Choice(o.getString("camera"), o.getInt("w"), o.getInt("h"), o.getInt("fps"), o.getInt("quality"))

}
