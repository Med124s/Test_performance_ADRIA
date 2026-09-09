const express = require('express')
const cors = require('cors')
const os = require('os')
const si = require('systeminformation')

const app = express()
app.use(cors())

const PORT = process.env.PORT || 5000

function classifyHealth(cpu, ram, disk) {
  if (cpu >= 90 || ram >= 90 || disk >= 95) return 'Critical'
  if (cpu >= 75 || ram >= 75 || disk >= 85) return 'Degraded'
  return 'Healthy'
}

// CPU et RAM : calculés avec les fonctions natives de Node (`os`), jamais
// bloquantes, plutôt que `systeminformation`. Diagnostic réel fait sur cette
// machine (voir conversation) : `si.mem()` et `si.fsSize()`/`si.networkStats()`
// ne répondent JAMAIS (WMI/PowerShell sous-jacent resté bloqué, sans erreur
// ni timeout côté librairie) et `si.currentLoad()` prend ~6s à répondre —
// bien plus que ce qu'un test de charge en cours peut se permettre d'attendre
// après chaque étape. `os.cpus()`/`os.totalmem()`/`os.freemem()` sont
// synchrones et instantanés, donc jamais sujets à ce blocage.
function sampleCpuTimes() {
  return os.cpus().map((c) => ({ idle: c.times.idle, total: Object.values(c.times).reduce((a, b) => a + b, 0) }))
}

function currentCpuPercent() {
  return new Promise((resolve) => {
    const start = sampleCpuTimes()
    setTimeout(() => {
      const end = sampleCpuTimes()
      let idleDiff = 0
      let totalDiff = 0
      for (let i = 0; i < start.length; i++) {
        idleDiff += end[i].idle - start[i].idle
        totalDiff += end[i].total - start[i].total
      }
      resolve(totalDiff > 0 ? Math.round((1 - idleDiff / totalDiff) * 100) : 0)
    }, 200)
  })
}

function currentRamPercent() {
  const total = os.totalmem()
  const free = os.freemem()
  return total > 0 ? Math.round(((total - free) / total) * 100) : 0
}

// Disk/Network n'ont pas d'équivalent natif dans Node — on garde
// `systeminformation` pour ces deux-là, mais bornés dans le temps : un appel
// qui traîne (même comportement observé que mem()/currentLoad() ci-dessus)
// retombe sur une valeur neutre plutôt que de geler toute la réponse.
const CALL_TIMEOUT_MS = 1500

function withTimeout(promise, fallback) {
  return Promise.race([
    promise,
    new Promise((resolve) => setTimeout(() => resolve(fallback), CALL_TIMEOUT_MS)),
  ]).catch(() => fallback)
}

app.get('/server-metrics', async (req, res) => {
  try {
    const [cpu, fsSize, netStats] = await Promise.all([
      currentCpuPercent(),
      withTimeout(si.fsSize(), []),
      withTimeout(si.networkStats(), []),
    ])
    const ram = currentRamPercent()

    const primaryDisk = fsSize.reduce((largest, d) => (d.size > (largest ? largest.size : 0) ? d : largest), null)
    const disk = primaryDisk ? Math.round(primaryDisk.use) : 0

    const bytesPerSec = netStats.reduce((sum, n) => sum + (n.rx_sec || 0) + (n.tx_sec || 0), 0)
    const network = Math.round((bytesPerSec * 8) / 1_000_000)

    res.json({
      cpu,
      ram,
      disk,
      network,
      health: classifyHealth(cpu, ram, disk),
      capturedAt: new Date().toISOString(),
    })
  } catch (err) {
    res.status(500).json({ error: 'Impossible de lire les ressources du PC', details: String(err) })
  }
})

app.listen(PORT, () => {
  console.log(`Local Test Server monitoring API — http://localhost:${PORT}/server-metrics`)
})
