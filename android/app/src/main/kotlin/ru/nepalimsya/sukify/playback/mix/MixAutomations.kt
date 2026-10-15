package ru.nepalimsya.sukify.playback.mix

import ru.nepalimsya.sukify.playback.TransitionPlan
import ru.nepalimsya.sukify.playback.audio.DeckAutomation
import ru.nepalimsya.sukify.playback.audio.Envelope

/** План перехода → автоматизация обеих дек (плеер и предпрослушка в редакторе звучат одинаково). */
object MixAutomations {

    /** (уходящая, приходящая) — по медиавремени каждой деки. */
    fun forPlan(plan: TransitionPlan): Pair<DeckAutomation, DeckAutomation> {
        if (plan.lengthMs <= 0) return DeckAutomation.Neutral to DeckAutomation.Neutral
        val outStart = plan.outStartMs * 1000
        val inStart = plan.inStartMs * 1000
        val length = plan.lengthMs * 1000
        val shapes = plan.shapes
        if (shapes == null) {
            val out = Envelope.sampled(outStart, length) { t -> plan.curve.value(1f, 0f, t) }
            val inn = Envelope.sampled(inStart, length) { t -> plan.curve.value(0f, 1f, t) }
            return DeckAutomation(gain = out) to DeckAutomation(gain = inn)
        }
        val volume = MixShapes.volume(shapes.volume)
        val eq = MixShapes.eq(shapes.eq)
        val filter = MixShapes.filter(shapes.filter)
        val out = DeckAutomation(
            gain = DeckAutomation.envelope(volume.out, outStart, length)!!,
            low = DeckAutomation.envelope(eq.out.low, outStart, length),
            mid = DeckAutomation.envelope(eq.out.mid, outStart, length),
            high = DeckAutomation.envelope(eq.out.high, outStart, length),
            filterType = filter.out?.type,
            filter = DeckAutomation.envelope(filter.out?.points, outStart, length),
            noise = DeckAutomation.envelope(filter.noise, outStart, length),
        )
        val inn = DeckAutomation(
            gain = DeckAutomation.envelope(volume.inn, inStart, length)!!,
            low = DeckAutomation.envelope(eq.inn.low, inStart, length),
            mid = DeckAutomation.envelope(eq.inn.mid, inStart, length),
            high = DeckAutomation.envelope(eq.inn.high, inStart, length),
            filterType = filter.inn?.type,
            filter = DeckAutomation.envelope(filter.inn?.points, inStart, length),
        )
        return out to inn
    }
}
