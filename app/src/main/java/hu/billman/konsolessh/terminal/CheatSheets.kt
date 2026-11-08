package hu.billman.konsolessh.terminal

/**
 * A Linux- és tmux-puska tartalma ANSI-színezett terminálszövegként.
 * Tartalom, nem felület: a megjelenítő oldal csak kiírja.
 */
@Suppress("LongMethod")
object CheatSheets {

    fun linux(hungarian: Boolean): String = if (hungarian) cheatSheetHu() else cheatSheetEn()

    fun tmux(hungarian: Boolean): String = if (hungarian) tmuxSheetHu() else tmuxSheetEn()

    private fun cheatSheetHu(): String {
        val r = "\u001b[0m"
        val b = "\u001b[1m"
        val h = "\u001b[38;5;214m"
        val t = "\u001b[38;5;117m"
        val e = "\u001b[38;5;222m"
        val d = "\u001b[38;5;245m"
        val w = "\u001b[38;5;203m"
        val s = "\u001b[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        fun warn(text: String) = "    $w⚠  $text$r\r\n"
        return buildString {
            append("\r\n $h$b── Linux parancssori kézikönyv ──$r\r\n")
            append(sec("top — folyamatok és rendszerterhelés"))
            append(cmd("top"))
            append("\r\n")
            append(desc("Valós idejű nézet: CPU, memória, futó folyamatok."))
            append(desc("Billentyűk: q=kilép  k=kill  M=mem szerint  P=CPU szerint"))
            append(ex("top -b -n1 | head -30"))
            append(sec("df — fájlrendszer lemezhasználat"))
            append(cmd("df -h"))
            append("\r\n")
            append(desc("-h: emberbarát egységek  -i: inode használat"))
            append(ex("df -h /home"))
            append(ex("df -h | grep -v tmpfs"))
            append(sec("du — könyvtár/fájl mérete"))
            append(cmd("du -sh *"))
            append("\r\n")
            append(desc("-s: összesített  -h: emberbarát  --max-depth=1"))
            append(ex("du -sh * | sort -rh | head -10"))
            append(sec("dd — nyers blokk-szintű adatmásolás"))
            append(cmd("dd if=forrás of=cél bs=4M status=progress"))
            append("\r\n")
            append(warn("Nincs visszavonás. A of= tartalma véglegesen felülíródik!"))
            append(sec("tail / head"))
            append(cmd("tail -f /var/log/syslog"))
            append("\r\n")
            append(ex("tail -n 50 fájl"))
            append(ex("head -n 5 /etc/passwd"))
            append(sec("grep / egrep"))
            append(cmd("grep 'minta' fájl"))
            append("\r\n")
            append(desc("-i: kis/nagybetű  -v: negálás  -n: sorszám  -r: rekurzív"))
            append(ex("grep -rn 'TODO' /home/projekt/"))
            append(ex("egrep '(error|warn|crit)' /var/log/syslog"))
            append(sec("awk — mezőalapú feldolgozás"))
            append(cmd("awk '{print \$1}' fájl"))
            append("\r\n")
            append(ex("awk -F: '{print \$1}' /etc/passwd"))
            append(ex("df -h | awk 'NR>1 {print \$5, \$6}'"))
            append(sec("sed — folyamszerkesztő"))
            append(cmd("sed 's/régi/új/g' fájl"))
            append("\r\n")
            append(ex("sed -i 's/foo/bar/g' fajl.txt"))
            append(warn("sed -i felülírja a fájlt!"))
            append(sec("ip — hálózati interfészek"))
            append(cmd("ip addr"))
            append("\r\n")
            append(ex("ip route"))
            append(ex("ip neigh"))
            append(sec("mc — Midnight Commander"))
            append(cmd("mc"))
            append("\r\n")
            append(desc("F5=másol  F6=mozgat  F8=töröl  F9=menü  F10=kilép"))
            append(sec("tr — karaktercsere"))
            append(cmd("echo 'Hello' | tr 'a-z' 'A-Z'"))
            append("\r\n")
            append(ex("cat fajl | tr -d '\\r'"))
            append("\r\n")
        }
    }

    private fun cheatSheetEn(): String {
        val r = "[0m"
        val b = "[1m"
        val h = "[38;5;214m"
        val t = "[38;5;117m"
        val e = "[38;5;222m"
        val d = "[38;5;245m"
        val w = "[38;5;203m"
        val s = "[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        fun warn(text: String) = "    $w⚠  $text$r\r\n"
        return buildString {
            append("\r\n $h$b── Linux command-line handbook ──$r\r\n")
            append(sec("top — processes and system load"))
            append(cmd("top"))
            append("\r\n")
            append(desc("Real-time view: CPU, memory, running processes."))
            append(desc("Keys: q=quit  k=kill  M=sort by mem  P=sort by CPU"))
            append(ex("top -b -n1 | head -30"))
            append(sec("df — filesystem disk usage"))
            append(cmd("df -h"))
            append("\r\n")
            append(desc("-h: human-readable units  -i: inode usage"))
            append(ex("df -h /home"))
            append(ex("df -h | grep -v tmpfs"))
            append(sec("du — directory/file size"))
            append(cmd("du -sh *"))
            append("\r\n")
            append(desc("-s: summary  -h: human-readable  --max-depth=1"))
            append(ex("du -sh * | sort -rh | head -10"))
            append(sec("dd — raw block-level copy"))
            append(cmd("dd if=source of=dest bs=4M status=progress"))
            append("\r\n")
            append(warn("No undo. The content of of= is permanently overwritten!"))
            append(sec("tail / head"))
            append(cmd("tail -f /var/log/syslog"))
            append("\r\n")
            append(ex("tail -n 50 file"))
            append(ex("head -n 5 /etc/passwd"))
            append(sec("grep / egrep"))
            append(cmd("grep 'pattern' file"))
            append("\r\n")
            append(desc("-i: case-insensitive  -v: invert  -n: line number  -r: recursive"))
            append(ex("grep -rn 'TODO' /home/project/"))
            append(ex("egrep '(error|warn|crit)' /var/log/syslog"))
            append(sec("awk — field-based processing"))
            append(cmd("awk '{print \$1}' file"))
            append("\r\n")
            append(ex("awk -F: '{print \$1}' /etc/passwd"))
            append(ex("df -h | awk 'NR>1 {print \$5, \$6}'"))
            append(sec("sed — stream editor"))
            append(cmd("sed 's/old/new/g' file"))
            append("\r\n")
            append(ex("sed -i 's/foo/bar/g' file.txt"))
            append(warn("sed -i overwrites the file!"))
            append(sec("ip — network interfaces"))
            append(cmd("ip addr"))
            append("\r\n")
            append(ex("ip route"))
            append(ex("ip neigh"))
            append(sec("mc — Midnight Commander"))
            append(cmd("mc"))
            append("\r\n")
            append(desc("F5=copy  F6=move  F8=delete  F9=menu  F10=quit"))
            append(sec("tr — character translation"))
            append(cmd("echo 'Hello' | tr 'a-z' 'A-Z'"))
            append("\r\n")
            append(ex("cat file | tr -d '\\r'"))
            append("\r\n")
        }
    }

    private fun tmuxSheetHu(): String {
        val r = "\u001b[0m"
        val b = "\u001b[1m"
        val h = "\u001b[38;5;214m"
        val t = "\u001b[38;5;117m"
        val e = "\u001b[38;5;222m"
        val d = "\u001b[38;5;245m"
        val s = "\u001b[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r\r\n"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        return buildString {
            append("\r\n $h$b── Tmux kézikönyv ──$r\r\n")
            append(sec("Mi a tmux"))
            append(desc("Terminál multiplexer: több ablak és panel egyetlen terminálon belül."))
            append(desc("Folyamatok futnak kapcsolat megszakadása után is."))
            append(sec("Alapfogalmak"))
            append(desc("Session → teljes munkakörnyezet"))
            append(desc("Window  → fül a sessionön belül"))
            append(desc("Pane    → ablakon belüli osztás"))
            append(sec("Session kezelés"))
            append(cmd("tmux new"))
            append(ex("tmux new -s munka"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t munka"))
            append(ex("tmux attach -d -t munka"))
            append(ex("tmux kill-session -t munka"))
            append(ex("tmux kill-server"))
            append(sec("Prefix"))
            append(cmd("Ctrl+b"))
            append(desc("Minden billentyűparancs ezzel kezdődik."))
            append(sec("Session műveletek"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b \$        # rename session"))
            append(ex("tmux rename-session -t regi uj"))
            append(ex("tmux switch-client -t nev"))
            append(sec("Window műveletek"))
            append(cmd("Ctrl+b c        # új window"))
            append(cmd("Ctrl+b w        # window lista"))
            append(cmd("Ctrl+b n        # következő"))
            append(cmd("Ctrl+b p        # előző"))
            append(cmd("Ctrl+b ,        # átnevezés"))
            append(cmd("Ctrl+b 0..9     # ugrás sorszámra"))
            append(sec("Pane műveletek"))
            append(cmd("Ctrl+b %        # vízszintes osztás"))
            append(cmd("Ctrl+b \"        # függőleges osztás"))
            append(cmd("Ctrl+b o        # következő pane"))
            append(cmd("Ctrl+b ;        # előző pane"))
            append(cmd("Ctrl+b x        # pane bezárása"))
            append(cmd("Ctrl+b z        # zoom (toggle)"))
            append(cmd("Ctrl+b {        # mozgatás balra"))
            append(cmd("Ctrl+b }        # mozgatás jobbra"))
            append(cmd("Ctrl+b !        # pane → window"))
            append(sec("Navigáció"))
            append(cmd("Ctrl+b ←↑→↓    # pane-ek között"))
            append(sec("Resize"))
            append(cmd("Ctrl+b Ctrl+←↑→↓"))
            append(sec("Layout"))
            append(cmd("Ctrl+b Space    # layout váltás"))
            append(sec("Scroll"))
            append(cmd("Ctrl+b [        # scroll mód"))
            append(cmd("q              # kilépés scroll módból"))
            append(sec("Paste"))
            append(cmd("Ctrl+b ]        # beillesztés"))
            append(sec("Config (~/.tmux.conf)"))
            append(ex("set -g mouse on"))
            append(ex("set -g history-limit 100000"))
            append(ex("setw -g mode-keys vi"))
            append(sec("Gyors lista"))
            append(ex("tmux new -s dev"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t dev"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b c        # új window"))
            append(cmd("Ctrl+b %        # vízszintes osztás"))
            append(cmd("Ctrl+b \"        # függőleges osztás"))
            append(cmd("Ctrl+b o        # váltás pane-ek között"))
            append(cmd("Ctrl+b z        # zoom"))
            append(cmd("Ctrl+b [        # scroll"))
            append("\r\n")
        }
    }

    private fun tmuxSheetEn(): String {
        val r = "\u001b[0m"
        val b = "\u001b[1m"
        val h = "\u001b[38;5;214m"
        val t = "\u001b[38;5;117m"
        val e = "\u001b[38;5;222m"
        val d = "\u001b[38;5;245m"
        val s = "\u001b[38;5;71m"
        fun sec(name: String) = "\r\n $s$b▸ $name$r\r\n"
        fun cmd(c: String) = "  $t$b$c$r\r\n"
        fun desc(text: String) = "    $d$text$r\r\n"
        fun ex(text: String) = "    $e$ $text$r\r\n"
        return buildString {
            append("\r\n $h$b── Tmux handbook ──$r\r\n")
            append(sec("What is tmux"))
            append(desc("Terminal multiplexer: multiple windows and panes inside a single terminal."))
            append(desc("Processes keep running after the connection drops."))
            append(sec("Core concepts"))
            append(desc("Session → full work environment"))
            append(desc("Window  → tab within the session"))
            append(desc("Pane    → split inside a window"))
            append(sec("Session management"))
            append(cmd("tmux new"))
            append(ex("tmux new -s work"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t work"))
            append(ex("tmux attach -d -t work"))
            append(ex("tmux kill-session -t work"))
            append(ex("tmux kill-server"))
            append(sec("Prefix"))
            append(cmd("Ctrl+b"))
            append(desc("Every key binding starts with this prefix."))
            append(sec("Session actions"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b \$        # rename session"))
            append(ex("tmux rename-session -t old new"))
            append(ex("tmux switch-client -t name"))
            append(sec("Window actions"))
            append(cmd("Ctrl+b c        # new window"))
            append(cmd("Ctrl+b w        # window list"))
            append(cmd("Ctrl+b n        # next"))
            append(cmd("Ctrl+b p        # previous"))
            append(cmd("Ctrl+b ,        # rename"))
            append(cmd("Ctrl+b 0..9     # jump to index"))
            append(sec("Pane actions"))
            append(cmd("Ctrl+b %        # horizontal split"))
            append(cmd("Ctrl+b \"        # vertical split"))
            append(cmd("Ctrl+b o        # next pane"))
            append(cmd("Ctrl+b ;        # previous pane"))
            append(cmd("Ctrl+b x        # close pane"))
            append(cmd("Ctrl+b z        # zoom (toggle)"))
            append(cmd("Ctrl+b {        # move left"))
            append(cmd("Ctrl+b }        # move right"))
            append(cmd("Ctrl+b !        # pane → window"))
            append(sec("Navigation"))
            append(cmd("Ctrl+b ←↑→↓    # between panes"))
            append(sec("Resize"))
            append(cmd("Ctrl+b Ctrl+←↑→↓"))
            append(sec("Layout"))
            append(cmd("Ctrl+b Space    # switch layout"))
            append(sec("Scroll"))
            append(cmd("Ctrl+b [        # scroll mode"))
            append(cmd("q              # exit scroll mode"))
            append(sec("Paste"))
            append(cmd("Ctrl+b ]        # paste"))
            append(sec("Config (~/.tmux.conf)"))
            append(ex("set -g mouse on"))
            append(ex("set -g history-limit 100000"))
            append(ex("setw -g mode-keys vi"))
            append(sec("Quick list"))
            append(ex("tmux new -s dev"))
            append(ex("tmux ls"))
            append(ex("tmux attach -t dev"))
            append(cmd("Ctrl+b d        # detach"))
            append(cmd("Ctrl+b c        # new window"))
            append(cmd("Ctrl+b %        # horizontal split"))
            append(cmd("Ctrl+b \"        # vertical split"))
            append(cmd("Ctrl+b o        # switch pane"))
            append(cmd("Ctrl+b z        # zoom"))
            append(cmd("Ctrl+b [        # scroll"))
            append("\r\n")
        }
    }
}
