package io.osrsx.plugins.skilling

import io.osrsx.api.player.Skill
import io.osrsx.plugin.PluginSettings
import io.osrsx.plugin.isTrue
import io.osrsx.plugin.Gfx2D
import io.osrsx.script.ScriptPlugin
import io.osrsx.script.ScriptScope
import io.osrsx.script.depositAll
import io.osrsx.script.openBank
import io.osrsx.script.script
import io.osrsx.script.withdrawItem
import io.osrsx.util.Rng
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sample cooking plugin: use raw food on a range/fire, cook the whole batch, then (optionally) bank the
 * cooked food and withdraw more raw. A processing skill — the inventory starts full of raw food, so the
 * loop is "process until none left, then restock" rather than "gather until full".
 *
 * Authored with the **Script DSL** ([ScriptPlugin]): the whole bot is a linear `script { }` body whose
 * cooperative waits ([ScriptScope.sleep] / [ScriptScope.waitUntil] / [ScriptScope.waitWhile]) yield the loop
 * thread between actions. It still carries the shared skiller scaffolding (login/yield/stop/input-lock/run/
 * dialogue/antiban/break) inline at the top of each iteration, and keeps skilling-lib's [SkillStats] /
 * [StopTargets] for XP tracking, the alt-drag [SkillOverlay] and the stop-at-level / stop-after-minutes
 * targets. The [ScriptPlugin]'s data box surfaces the live [status] automatically.
 */
class CookerPlugin : ScriptPlugin() {

    object Config : PluginSettings("cooker") {
        var raw by itemItem("raw", "Raw food", "Raw shrimps", "Item name of the raw food to cook")
        // The cooked name + return tile only matter when banking/restocking — hidden otherwise.
        var cooked by itemItem("cooked", "Cooked food", "Shrimps", "Item name once cooked (banked on restock)",
            visibleIf = isTrue("restock"))
        var range by objectItem("range", "Range/fire", "Range", "Object to cook on",
            filter = listOf("range", "fire", "stove", "cooking"), browse = true, distinct = true)
        var restock by boolItem("restock", "Bank & restock", true, "Bank cooked food and withdraw more raw when out")
        var home by stringItem("home", "Range tile", "", "Optional 'x,y[,plane]' to walk back to after banking",
            visibleIf = isTrue("restock"))

        var lockInput by boolItem("lockInput", "Lock user input", false,
            "While running, ignore physical mouse/keyboard input so it can't disrupt the bot", section = "Antiban")
        var stopAtLevel by intItem("stopAtLevel", "Stop at level", 0, 0, 99, "Stop when Cooking hits this level (0 = never)", "Stopping")
        var stopAfterMins by intItem("stopAfterMins", "Stop after (min)", 0, 0, 100_000, "Stop after this many minutes (0 = never)", "Stopping")
    }

    override fun settings() = Config

    private val stats by lazy { SkillStats(ctx, Skill.COOKING) }
    private val stops by lazy {
        StopTargets(stats, level = { Config.stopAtLevel }, count = { 0 }, gp = { 0 }, minutes = { Config.stopAfterMins })
    }

    override fun onScriptStart() {
        stats.start()
        stats.carried = { inventory.count(Config.cooked) }
    }

    override fun onScriptStop() = releaseInput()

    override fun script() = script("Cooking") {
        while (!isStopping) {
            if (!login.isLoggedIn()) { login.login(); sleep(1500L); continue }
            if (coordination.shouldYield()) { sleep(1200L..2000L); continue } // defer to a higher-priority plugin
            val stopReason = stops.reason()
            if (stopReason != null) { log.i("stopping — $stopReason"); break }
            applyInputLock()
            if (breaks.onBreak()) { sleep(2000L..5000L); continue }  // account-wide break: idle
            walker.local.manageRun()
            if (dialogues.inDialogue()) { dialogues.continueAuto(); sleep(600L..1000L); continue }
            val idle = antibanIdle()
            if (idle != null) { sleep(idle); continue }

            if (Config.raw in inventory) cook() else if (!restock()) break
        }
        releaseInput()
    }

    /** Use a raw item on the range/fire, choose cook-all, then wait out the batch. */
    private suspend fun ScriptScope.cook() {
        report("cooking")
        val range = objects.query().named(Config.range).nearest() ?: run {
            report("walking")
            sleep(if (walkHome()) 800L..1200L else 1200L..2000L)
            return
        }
        val raw = inventory.getItem(Config.raw) ?: run { sleep(600L..1000L); return }
        inventory.useOn(raw, range)
        sleep(900L..1500L)
        // "How many would you like to cook?" — pick cook-all, then let the whole batch run.
        if (waitUntil(5.seconds) { dialogues.makeQuantity() }) sleep(1600L..2600L)
        waitWhile(60.seconds, 700.milliseconds) { isAnimating() }
    }

    /** Bank the cooked batch and withdraw a fresh inventory of raw. Returns false to stop (restock off). */
    private suspend fun ScriptScope.restock(): Boolean {
        if (!Config.restock) {
            report("out of raw")
            log.i("out of raw food — stopping")
            return false
        }
        report("banking")
        if (!openBank()) { sleep(1000L..1600L); return true }
        if (Config.cooked in inventory) {
            stats.addProduced(inventory.count(Config.cooked)) // tally the cooked batch before banking it
            depositAll(Config.cooked)                          // cooked batch (and any burnt leftovers)
        }
        if (Config.raw in inventory) depositAll(Config.raw)
        withdrawItem(Config.raw, 0)                            // a fresh inventory of raw food (0 = All)
        bank.close()
        if (walkHome()) report("walking")                     // head back to the range; next loop resumes cooking
        sleep(600L..1000L)
        return true
    }

    /** Update both readouts: the overlay reads [SkillStats.status], the data box reads [ScriptScope.status]. */
    private fun ScriptScope.report(text: String) {
        stats.status = text
        status(text)
    }

    private fun isAnimating(): Boolean = (players.localPlayer()?.animation ?: IDLE) != IDLE

    /** Web-walk back toward the configured range tile if set and not yet there; true while still travelling. */
    private fun walkHome(): Boolean {
        val home = configuredTile(Config.home) ?: return false
        if (walker.global.arrived(home)) return false
        walker.global.pathTo(home)
        return true
    }

    private fun applyInputLock() {
        val want = Config.lockInput
        if (want && !input.isLocked()) input.lock()
        else if (!want && input.isLocked()) input.unlock()
    }

    private fun releaseInput() { if (input.isLocked()) input.unlock() }

    private fun antibanIdle(): Long? =
        if (Rng.chance(IDLE_CHANCE)) Rng.uniform(IDLE_MIN_MS, IDLE_MAX_MS) else null

    override fun onPanel(gfx: Gfx2D) {
        gfx.overlay("Cooking") { g -> SkillOverlay.render(g, stats, listOf("Cooked" to SkillOverlay.commas(stats.produced()))) }
    }

    private companion object {
        const val IDLE = -1
        const val IDLE_CHANCE = 0.03
        const val IDLE_MIN_MS = 1500L
        const val IDLE_MAX_MS = 4000L
    }
}
