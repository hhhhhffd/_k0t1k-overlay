using System;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

public static class Cap
{
    [StructLayout(LayoutKind.Sequential, Pack = 2)]
    public struct WAVEFORMATEX
    {
        public ushort wFormatTag, nChannels; public uint nSamplesPerSec, nAvgBytesPerSec;
        public ushort nBlockAlign, wBitsPerSample, cbSize;
    }

    [ComImport, Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioClient
    {
        [PreserveSig] int Initialize(int shareMode, uint flags, long bufferDuration, long periodicity, ref WAVEFORMATEX fmt, IntPtr sessionGuid);
        [PreserveSig] int GetBufferSize(out uint frames);
        [PreserveSig] int GetStreamLatency(out long latency);
        [PreserveSig] int GetCurrentPadding(out uint frames);
        [PreserveSig] int IsFormatSupported(int shareMode, IntPtr fmt, out IntPtr closest);
        [PreserveSig] int GetMixFormat(out IntPtr fmt);
        [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minPeriod);
        [PreserveSig] int Start();
        [PreserveSig] int Stop();
        [PreserveSig] int Reset();
        [PreserveSig] int SetEventHandle(IntPtr eventHandle);
        [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object service);
    }

    [ComImport, Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAudioCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePos, out ulong qpcPos);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("72A22D78-CDE4-431D-B8CC-843A71199B6D"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivateAudioInterfaceAsyncOperation
    {
        [PreserveSig] int GetActivateResult(out int activateResult, [MarshalAs(UnmanagedType.IUnknown)] out object activatedInterface);
    }

    [ComImport, Guid("41D949AB-9862-444A-80F6-C261334DA5EB"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IActivateAudioInterfaceCompletionHandler
    {
        [PreserveSig] int ActivateCompleted(IActivateAudioInterfaceAsyncOperation operation);
    }

    // Marker interface: ActivateAudioInterfaceAsync refuses handlers that do not answer QueryInterface(IAgileObject).
    [ComImport, Guid("94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IAgileObject { }

    [ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDevice
    {
        [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr activationParams, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    }

    [ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    public interface IMMDeviceEnumerator
    {
        [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IntPtr devices);
        [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice device);
    }

    public class Handler : IActivateAudioInterfaceCompletionHandler, IAgileObject
    {
        public readonly ManualResetEvent Done = new ManualResetEvent(false);
        public IActivateAudioInterfaceAsyncOperation Operation;
        public int ActivateCompleted(IActivateAudioInterfaceAsyncOperation op) { Operation = op; Done.Set(); return 0; }
    }

    [DllImport("Mmdevapi.dll", ExactSpelling = true, PreserveSig = false)]
    static extern void ActivateAudioInterfaceAsync([MarshalAs(UnmanagedType.LPWStr)] string path, ref Guid iid,
        IntPtr activationParams, IActivateAudioInterfaceCompletionHandler handler, out IActivateAudioInterfaceAsyncOperation op);

    static readonly Guid IID_IAudioClient = new Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2");
    static readonly Guid IID_IAudioCaptureClient = new Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317");
    const uint LOOPBACK = 0x00020000, EVENTCALLBACK = 0x00040000, AUTOCONVERTPCM = 0x80000000, SRC_DEFAULT_QUALITY = 0x08000000;
    const int Rate = 48000, FrameBytes = 4; // s16le stereo

    static void Check(int hr, string what) { if (hr < 0) throw new COMException(what, hr); }

    // mode: "game" = only pid's tree, "rest" = everything except pid's tree, "mic" = default capture device.
    public static void Run(string mode, int pid)
    {
        IAudioClient client = mode == "mic" ? OpenMic() : OpenProcessLoopback(pid, mode == "rest");
        var fmt = new WAVEFORMATEX { wFormatTag = 1, nChannels = 2, nSamplesPerSec = Rate, wBitsPerSample = 16,
            nBlockAlign = FrameBytes, nAvgBytesPerSec = Rate * FrameBytes };
        uint flags = EVENTCALLBACK | AUTOCONVERTPCM | SRC_DEFAULT_QUALITY | (mode == "mic" ? 0 : LOOPBACK);
        Check(client.Initialize(0 /*SHARED*/, flags, 2000000 /*200 ms*/, 0, ref fmt, IntPtr.Zero), "Initialize");
        var wake = new AutoResetEvent(false);
        Check(client.SetEventHandle(wake.SafeWaitHandle.DangerousGetHandle()), "SetEventHandle");
        Guid iid = IID_IAudioCaptureClient; object o;
        Check(client.GetService(ref iid, out o), "GetService");
        var capture = (IAudioCaptureClient)o;

        var stop = new ManualResetEvent(false); // parent closing our stdin = stop
        new Thread(() => { var s = Console.OpenStandardInput(); var b = new byte[64]; while (s.Read(b, 0, b.Length) > 0) { } stop.Set(); }) { IsBackground = true }.Start();

        Stream stdout = Console.OpenStandardOutput();
        Check(client.Start(), "Start");
        var clock = Stopwatch.StartNew();
        long sentFrames = 0;
        var buf = new byte[Rate * FrameBytes]; // 1 s, also used as zeros
        while (!stop.WaitOne(0))
        {
            wake.WaitOne(10);
            uint packet;
            Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
            while (packet > 0)
            {
                IntPtr data; uint frames, bufFlags; ulong dp, qpc;
                Check(capture.GetBuffer(out data, out frames, out bufFlags, out dp, out qpc), "GetBuffer");
                int bytes = (int)frames * FrameBytes;
                if (bytes > buf.Length) buf = new byte[bytes];
                if ((bufFlags & 2) != 0) Array.Clear(buf, 0, bytes); else Marshal.Copy(data, buf, 0, bytes);
                stdout.Write(buf, 0, bytes);
                sentFrames += frames;
                Check(capture.ReleaseBuffer(frames), "ReleaseBuffer");
                Check(capture.GetNextPacketSize(out packet), "GetNextPacketSize");
            }
            // Loopback delivers no packets while nothing plays; fill the gap so the stream stays real-time.
            long due = clock.ElapsedTicks * Rate / Stopwatch.Frequency;
            while (due - sentFrames >= Rate / 100) // >= 10 ms behind
            {
                int frames = (int)Math.Min(due - sentFrames, buf.Length / FrameBytes);
                Array.Clear(buf, 0, frames * FrameBytes);
                stdout.Write(buf, 0, frames * FrameBytes);
                sentFrames += frames;
            }
            stdout.Flush();
        }
        client.Stop();
    }

    static IAudioClient OpenProcessLoopback(int pid, bool excludeTree)
    {
        // AUDIOCLIENT_ACTIVATION_PARAMS { int type=1 (PROCESS_LOOPBACK); uint pid; int mode (0 include, 1 exclude) }
        IntPtr p = Marshal.AllocHGlobal(12);
        Marshal.WriteInt32(p, 0, 1); Marshal.WriteInt32(p, 4, pid); Marshal.WriteInt32(p, 8, excludeTree ? 1 : 0);
        // PROPVARIANT (x64): vt=VT_BLOB(65) @0, cbSize @8, pBlobData @16
        IntPtr pv = Marshal.AllocHGlobal(24);
        for (int i = 0; i < 24; i += 8) Marshal.WriteInt64(pv, i, 0);
        Marshal.WriteInt16(pv, 0, 65); Marshal.WriteInt32(pv, 8, 12); Marshal.WriteIntPtr(pv, 16, p);
        var handler = new Handler();
        Guid iid = IID_IAudioClient; IActivateAudioInterfaceAsyncOperation op;
        ActivateAudioInterfaceAsync("VAD\\Process_Loopback", ref iid, pv, handler, out op);
        if (!handler.Done.WaitOne(5000)) throw new TimeoutException("ActivateAudioInterfaceAsync");
        int hr; object unk;
        Check(handler.Operation.GetActivateResult(out hr, out unk), "GetActivateResult");
        Check(hr, "process loopback activation (needs Windows 10 2004+)");
        return (IAudioClient)unk;
    }

    static IAudioClient OpenMic()
    {
        var enumerator = (IMMDeviceEnumerator)Activator.CreateInstance(Type.GetTypeFromCLSID(new Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")));
        IMMDevice dev; Check(enumerator.GetDefaultAudioEndpoint(1 /*eCapture*/, 0 /*eConsole*/, out dev), "no capture device");
        Guid iid = IID_IAudioClient; object o;
        Check(dev.Activate(ref iid, 23 /*CLSCTX_ALL*/, IntPtr.Zero, out o), "Activate");
        return (IAudioClient)o;
    }
}
