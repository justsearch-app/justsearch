package conventions

object HostPlatform {
  private val os = System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT)
  val isWindows = os.contains("windows")
  val isLinux = os.startsWith("linux")
  val targetId: String
    get() {
      val arch = System.getProperty("os.arch", "").lowercase(java.util.Locale.ROOT)
      val cpu = if (arch == "amd64" || arch == "x64") "x86_64" else arch
      return "${if (isWindows) "windows" else os}-$cpu"
    }
}
