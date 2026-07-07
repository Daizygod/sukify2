/**
 * MVP "smart shuffle": a weighted shuffle, not an ML recommender. It avoids
 * playing the same artist back-to-back and lightly favours liked tracks.
 * Deliberately isolated so the algorithm can be swapped without touching the
 * player.
 */
export function smartShuffle(tracks) {
  const pool = [...tracks]
  const result = []
  let lastArtistId = null

  while (pool.length) {
    // Weight each candidate.
    const weights = pool.map((t) => {
      let w = 1
      if (t.is_liked) w += 0.6
      const primary = t.artists?.[0]?.id
      if (primary && primary === lastArtistId) w *= 0.15 // strongly avoid repeats
      return w
    })

    const idx = weightedPick(weights)
    const [chosen] = pool.splice(idx, 1)
    result.push(chosen)
    lastArtistId = chosen.artists?.[0]?.id ?? null
  }

  return result
}

function weightedPick(weights) {
  const total = weights.reduce((a, b) => a + b, 0)
  let r = Math.random() * total
  for (let i = 0; i < weights.length; i++) {
    r -= weights[i]
    if (r <= 0) return i
  }
  return weights.length - 1
}
