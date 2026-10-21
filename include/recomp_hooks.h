#pragma once

#include "recomp.h"

// Implemented by the game host because the N64 routine uses PIF/COP0 behavior
// that cannot be expressed by the current recompiler output.
//
// RACER PATCH: MMIO interception hooks. On Android the 4GB virtual allocation
// is only partially mapped (512 MB RDRAM + extensions).  N64 device registers
// live at kseg1 addresses ~0xA4XXXXXX which translate to native offsets >
// 0x20000000 and are unmapped, causing SIGSEGV.  These hooks let the host
// intercept those accesses and either absorb them or emulate the register.
#ifdef __cplusplus
extern "C" {
#endif
void __osSiRawStartDma_recomp(uint8_t* rdram, recomp_context* ctx);

// RACER PATCH: called for any 32-bit device-register access.
//   Returns a pointer to writable memory so the caller's MEM_W lvalue can
//   write to it on stores, or read from it on loads.
void* rdram_mmio_read(uint32_t offset);
void* rdram_mmio_write(uint32_t offset);

// Runtime functions that tools/post_recomp_patches.py REDIRECTS route game functions to, when they are not already
// declared in RecompiledFuncs/funcs.h (implemented in N64ModernRuntime librecomp/src/ultra_translation.cpp).
void osSetThreadPri_recomp(uint8_t* rdram, recomp_context* ctx);
void osSendMesg_recomp(uint8_t* rdram, recomp_context* ctx);
void osJamMesg_recomp(uint8_t* rdram, recomp_context* ctx);
void osViGetCurrentFramebuffer_recomp(uint8_t* rdram, recomp_context* ctx);
void osGetTime_recomp(uint8_t* rdram, recomp_context* ctx);
void osEepromProbe_recomp(uint8_t* rdram, recomp_context* ctx);
void osEepromLongRead_recomp(uint8_t* rdram, recomp_context* ctx);
void osEepromLongWrite_recomp(uint8_t* rdram, recomp_context* ctx);
void osContSetCh_recomp(uint8_t* rdram, recomp_context* ctx);
void osContInit_recomp(uint8_t* rdram, recomp_context* ctx);
void osContStartReadData_recomp(uint8_t* rdram, recomp_context* ctx);
void osContGetReadData_recomp(uint8_t* rdram, recomp_context* ctx);
void osPfsInitPak_recomp(uint8_t* rdram, recomp_context* ctx);
void osMotorInit_recomp(uint8_t* rdram, recomp_context* ctx);
void racer_osMotorAccess(uint8_t* rdram, recomp_context* ctx);
void racer_osPiReadIo(uint8_t* rdram, recomp_context* ctx);
void osAiSetFrequency_recomp(uint8_t* rdram, recomp_context* ctx);
void osAiSetNextBuffer_recomp(uint8_t* rdram, recomp_context* ctx);
void osAiGetLength_recomp(uint8_t* rdram, recomp_context* ctx);
void osAiGetStatus_recomp(uint8_t* rdram, recomp_context* ctx);
void osViSwapBuffer_recomp(uint8_t* rdram, recomp_context* ctx);
void osViBlack_recomp(uint8_t* rdram, recomp_context* ctx);
void osViSetMode_recomp(uint8_t* rdram, recomp_context* ctx);
#ifdef __cplusplus
}
#endif
