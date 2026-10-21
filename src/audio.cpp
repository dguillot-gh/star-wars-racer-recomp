// Audio output for Star Wars Episode I: Racer (SDL2 queue).
//
// The runtime calls these three functions with the game's mixed output: interleaved stereo int16.
// N64 audio buffers come out with left/right swapped (RDRAM word order), so every pair is swapped back here,
// as Zelda64Recomp does.
// NOTE: the game's audio RSP microcode is still a no-op (get_rsp_microcode in main.cpp), so until S9 part B is done the
// samples are silence. The "[SWE1R] audio: queue" log line prints the peak level so you can tell (peak 0 = silence).

#include <SDL.h>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <vector>

static SDL_AudioDeviceID g_audio_dev = 0;
static uint32_t g_audio_freq = 0;

static void swe1r_open_audio(uint32_t freq) {
    if (SDL_WasInit(SDL_INIT_AUDIO) == 0) {
        SDL_InitSubSystem(SDL_INIT_AUDIO);
    }
    if (g_audio_dev != 0) {
        SDL_CloseAudioDevice(g_audio_dev);
        g_audio_dev = 0;
    }
    SDL_AudioSpec want{};
    want.freq = (int)freq;
    want.format = AUDIO_S16SYS;
    want.channels = 2;
    want.samples = 1024;
    want.callback = nullptr; // queue mode
    g_audio_dev = SDL_OpenAudioDevice(nullptr, 0, &want, nullptr, 0);
    if (g_audio_dev == 0) {
        printf("[SWE1R] audio: SDL_OpenAudioDevice(%u Hz) failed: %s\n", freq, SDL_GetError());
        return;
    }
    SDL_PauseAudioDevice(g_audio_dev, 0);
    printf("[SWE1R] audio: device open at %u Hz\n", freq);
}

void swe1r_queue_samples(int16_t* samples, size_t num_samples) {
    if (g_audio_dev == 0 || samples == nullptr || num_samples < 2) {
        return;
    }
    static std::vector<int16_t> buf;
    static uint32_t calls = 0;
    buf.resize(num_samples);
    int peak = 0;
    for (size_t i = 0; i + 1 < num_samples; i += 2) {
        buf[i] = samples[i + 1];
        buf[i + 1] = samples[i];
        const int a = std::abs((int)samples[i]);
        const int b = std::abs((int)samples[i + 1]);
        if (a > peak) peak = a;
        if (b > peak) peak = b;
    }
    // Do not let latency grow without bound if the game produces faster than real time.
    if (SDL_GetQueuedAudioSize(g_audio_dev) > 48000u * 4u / 2u) {
        SDL_ClearQueuedAudio(g_audio_dev);
    }
    SDL_QueueAudio(g_audio_dev, buf.data(), (Uint32)(num_samples * sizeof(int16_t)));
    calls++;
    if (calls <= 5 || (calls % 600) == 0) {
        printf("[SWE1R] audio: queue #%u samples=%zu peak=%d\n", calls, num_samples, peak);
    }
}

size_t swe1r_get_frames_remaining() {
    if (g_audio_dev == 0) {
        // Same value the old stub returned, so the game never stalls waiting for audio.
        return 2048;
    }
    return SDL_GetQueuedAudioSize(g_audio_dev) / (2 * sizeof(int16_t));
}

void swe1r_set_frequency(uint32_t freq) {
    if (freq == 0 || freq == g_audio_freq) {
        return;
    }
    printf("[SWE1R] Audio frequency set to %u Hz\n", freq);
    g_audio_freq = freq;
    swe1r_open_audio(freq);
}
