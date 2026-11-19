// Play for On-device AI pack holding the answering model (Gemma 3 1B int4, LiteRT-LM, 584 MB).
// On-demand: the store app downloads it the first time the student opens Ask, so the base app stays
// small and Search works without it. The model file is not committed (it's gated by the Gemma
// licence on Hugging Face): scripts/install_llm_pack.sh puts it in src/main/assets before a bundle build.
plugins {
    id("com.android.ai-pack")
}

aiPack {
    packName = "gemma_pack"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}
