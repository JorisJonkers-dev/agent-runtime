package com.jorisjonkers.personalstack.agentgateway.tmux

import com.jorisjonkers.personalstack.agentgateway.config.GatewayProperties
import com.jorisjonkers.personalstack.agentgateway.process.ProcessRunner
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.Path

/**
 * Wraps the tmux CLI. Every method shells out — the gateway never
 * embeds libtmux because tmux itself is the cheapest, most portable
 * implementation of "a pty I can stream from N readers at once".
 *
 * One tmux server per Pod (`-L <socketName>` keeps it off the user's
 * default server). Each agent is one session with one window with one
 * pane: nesting more than that adds no value and complicates
 * pipe-pane targeting.
 */
@Component
class TmuxClient(
    private val runner: ProcessRunner,
    private val props: GatewayProperties,
) {
    private val log = LoggerFactory.getLogger(TmuxClient::class.java)

    val stateDir: Path
        get() {
            val dir = Path(props.tmux.stateDir)
            Files.createDirectories(dir)
            return dir
        }

    fun newSession(
        name: String,
        command: List<String>,
        cwd: String,
        env: Map<String, String> = emptyMap(),
    ) {
        stateDir
        val argv =
            mutableListOf(
                "tmux",
                "-L",
                props.tmux.socketName,
                "new-session",
                "-d",
                "-s",
                name,
                "-x",
                "200",
                "-y",
                "50",
                "-c",
                cwd,
            ) + command
        runner.run(argv, env = env)
        // No client ever attaches to this session (the gateway tails the
        // pipe-pane log instead), so tmux's default client-driven sizing
        // never runs and a resize-window from the browser would be
        // recomputed away. window-size=manual makes the browser's resize
        // frames the sole authority over the pane geometry, so the TUI
        // renders at the xterm's real width instead of the 200x50
        // bootstrap size.
        runner.run(
            listOf("tmux", "-L", props.tmux.socketName, "set-option", "-t", name, "window-size", "manual"),
            checked = false,
        )
        // Geometry itself is driven by `resize-window` (which SIGWINCHes the
        // pane program), not by this option. focus-events on makes tmux forward
        // terminal focus in/out reports to the running TUI (e.g. Claude Code),
        // which some TUIs use to pause animations and refresh on focus; without
        // it they warn that focus reporting is off. Server-global (`-g`) on the
        // gateway's private socket so it applies to every agent session.
        runner.run(
            listOf("tmux", "-L", props.tmux.socketName, "set-option", "-g", "focus-events", "on"),
            checked = false,
        )
        log.info("tmux session {} created in {}", name, cwd)
    }

    fun killSession(name: String) {
        runner.run(
            listOf("tmux", "-L", props.tmux.socketName, "kill-session", "-t", name),
            checked = false,
        )
    }

    fun sendKeys(
        session: String,
        text: String,
        enter: Boolean = true,
    ) {
        val argv =
            mutableListOf(
                "tmux",
                "-L",
                props.tmux.socketName,
                "send-keys",
                "-t",
                "$session:0.0",
                "-l",
                text,
            )
        runner.run(argv)
        if (enter) {
            runner.run(
                listOf(
                    "tmux",
                    "-L",
                    props.tmux.socketName,
                    "send-keys",
                    "-t",
                    "$session:0.0",
                    "Enter",
                ),
            )
        }
    }

    fun sendKey(
        session: String,
        key: String,
    ) {
        runner.run(
            listOf(
                "tmux",
                "-L",
                props.tmux.socketName,
                "send-keys",
                "-t",
                "$session:0.0",
                key,
            ),
        )
    }

    fun capture(
        session: String,
        historyLines: Int = 1_000,
    ): String =
        runner
            .run(
                listOf(
                    "tmux",
                    "-L",
                    props.tmux.socketName,
                    "capture-pane",
                    "-p",
                    "-S",
                    "-$historyLines",
                    "-t",
                    "$session:0.0",
                ),
            ).stdout

    /**
     * Visible screen only, WITH ANSI escapes (`-e`), no `-S` history.
     * This is the one-shot snapshot a WS client renders on attach so a
     * full-screen TUI shows up immediately without replaying the log.
     */
    fun captureWithEscapes(session: String): String =
        runner
            .run(
                listOf(
                    "tmux",
                    "-L",
                    props.tmux.socketName,
                    "capture-pane",
                    "-e",
                    "-p",
                    "-t",
                    "$session:0.0",
                ),
            ).stdout

    fun resize(
        session: String,
        cols: Int,
        rows: Int,
    ) {
        runner.run(
            listOf(
                "tmux",
                "-L",
                props.tmux.socketName,
                "resize-window",
                "-t",
                "$session:0.0",
                "-x",
                cols.toString(),
                "-y",
                rows.toString(),
            ),
            checked = false,
        )
    }

    fun listSessions(): List<String> {
        val result =
            runner.run(
                listOf(
                    "tmux",
                    "-L",
                    props.tmux.socketName,
                    "list-sessions",
                    "-F",
                    "#{session_name}",
                ),
                checked = false,
            )
        if (result.exitCode != 0) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }

    fun startPipeToFile(
        session: String,
        file: Path,
    ) {
        val target = file.toAbsolutePath().normalize()
        runner.run(
            listOf(
                "tmux",
                "-L",
                props.tmux.socketName,
                "pipe-pane",
                "-O",
                "-t",
                "$session:0.0",
                "cat >> ${shellQuote(target.toString())}",
            ),
        )
    }

    fun sessionExists(name: String): Boolean = name in listSessions()

    internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"
}
