package dev.k0t1k.overlay.impl;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import static org.lwjgl.system.MemoryUtil.memAddress;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceCapabilitiesKHR;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Draws the overlay into the game's swapchain images when the game renders with Vulkan: Minecraft's
 * own backend (26.2+) or VulkanMod.
 *
 * <p>Right before the game presents, the hook records one render pass that loads the finished image,
 * draws the {@link Batch} over it and leaves it ready to present again. That work waits on the
 * semaphores the game handed to {@code vkQueuePresentKHR}, and the present then waits on the
 * overlay's own semaphore instead. Swapchains are created with colour attachment usage added, because
 * a game may only copy into its swapchain images, and with transfer source usage where the surface
 * allows it, so the recorder can read the game's frame back: before the render pass, the image is
 * scaled to the recording size into another one and copied into a host buffer that is read once the frame's fence
 * signals, a few frames later.
 *
 * <p>All of it runs on the thread that presents, which is the thread that owns the queue at that
 * moment, as Vulkan requires.
 */
final class VkRenderer implements VulkanBackend {
    private static final int FRAMES = 3;

    private final Overlay overlay;
    private boolean disabled;
    /** Queue handle to its family index, learnt from vkGetDeviceQueue. */
    private final Map<Long, Integer> queueFamilies = new HashMap<>();
    private final Map<Long, Swapchain> swapchains = new HashMap<>();
    private Device device;
    /** Families of the game's device that can run graphics work, by queue family index. */
    private boolean[] graphicsFamilies;
    /**
     * The game's present info as it was before the overlay pointed it at its own semaphore, so the
     * exit hook can put it back in case the game reuses the struct for its next present.
     */
    private long rewrittenInfo;
    private long originalWaits;
    private int originalWaitCount;
    /** Whether the swapchain last presented can be read back for the replay buffer. */
    private boolean recording;

    VkRenderer(Overlay overlay) {
        this.overlay = overlay;
    }

    @Override
    public void disable() {
        disabled = true;
    }

    @Override
    public boolean recording() {
        return recording && !disabled;
    }

    @Override
    public Object call(String method, Object[] args, Object result, boolean exit) {
        switch (method) {
            case "vkGetDeviceQueue":
                if (exit) {
                    PointerBuffer queue = (PointerBuffer) args[3];
                    queueFamilies.put(queue.get(queue.position()), (Integer) args[1]);
                }
                return null;
            case "vkGetDeviceQueue2":
                if (exit) {
                    PointerBuffer queue = (PointerBuffer) args[2];
                    queueFamilies.put(queue.get(queue.position()), ((VkDeviceQueueInfo2) args[1]).queueFamilyIndex());
                }
                return null;
            case "vkCreateSwapchainKHR":
                return exit ? swapchainCreated(args, result) : swapchainCreating(args);
            case "vkDestroySwapchainKHR":
                swapchainDestroyed((VkDevice) args[0], (Long) args[1]);
                return null;
            case "vkDestroyDevice":
                deviceDestroyed((VkDevice) args[0]);
                return null;
            case "vkQueuePresentKHR":
                if (exit) {
                    restorePresent((VkPresentInfoKHR) args[1]);
                } else if (!disabled) {
                    present((VkQueue) args[0], (VkPresentInfoKHR) args[1]);
                }
                return null;
            default:
                return null;
        }
    }

    // ---- swapchains ----

    private static final class Swapchain {
        final VkDevice device;
        final long handle;
        final int format;
        final int width;
        final int height;
        /** Created with transfer source usage, so the replay buffer can copy from its images. */
        final boolean readable;
        long[] images;
        long[] views;
        long[] framebuffers;
        /** Signalled by the overlay's draw for each image, waited on by its present. */
        long[] done;
        /** Native one-element arrays handed to the present as its wait semaphores. */
        LongBuffer[] waits;

        Swapchain(VkDevice device, long handle, int format, int width, int height, boolean readable) {
            this.device = device;
            this.handle = handle;
            this.format = format;
            this.width = width;
            this.height = height;
            this.readable = readable;
        }
    }

    private Object swapchainCreating(Object[] args) {
        VkSwapchainCreateInfoKHR info = (VkSwapchainCreateInfoKHR) args[1];
        // Every surface supports colour attachment use, so this never makes creation fail.
        int usage = info.imageUsage() | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        // Transfer source use is optional for a surface; without it the replay buffer stays off.
        VkDevice vkDevice = (VkDevice) args[0];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.malloc(stack);
            int result = vkGetPhysicalDeviceSurfaceCapabilitiesKHR(vkDevice.getPhysicalDevice(), info.surface(), capabilities);
            if (result == VK_SUCCESS && (capabilities.supportedUsageFlags() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0) {
                usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
            }
        }
        info.imageUsage(usage);
        return null;
    }

    private Object swapchainCreated(Object[] args, Object result) {
        if (!(result instanceof Integer) || (Integer) result != VK_SUCCESS) {
            return null;
        }
        VkDevice vkDevice = (VkDevice) args[0];
        VkSwapchainCreateInfoKHR info = (VkSwapchainCreateInfoKHR) args[1];
        long handle;
        if (args[3] instanceof LongBuffer) {
            LongBuffer out = (LongBuffer) args[3];
            handle = out.get(out.position());
        } else {
            handle = ((long[]) args[3])[0];
        }
        VkExtent2D extent = info.imageExtent();
        boolean readable = (info.imageUsage() & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0;
        swapchains.put(handle, new Swapchain(vkDevice, handle, info.imageFormat(), extent.width(), extent.height(), readable));
        return null;
    }

    private void swapchainDestroyed(VkDevice vkDevice, long handle) {
        Swapchain swapchain = swapchains.remove(handle);
        if (swapchain != null && device != null && swapchain.views != null) {
            device.waitIdleFrames();
            release(swapchain);
        }
    }

    private void release(Swapchain swapchain) {
        VkDevice d = swapchain.device;
        for (int i = 0; i < swapchain.views.length; i++) {
            vkDestroyFramebuffer(d, swapchain.framebuffers[i], null);
            vkDestroyImageView(d, swapchain.views[i], null);
            vkDestroySemaphore(d, swapchain.done[i], null);
            MemoryUtil.memFree(swapchain.waits[i]);
        }
        swapchain.views = null;
    }

    private void deviceDestroyed(VkDevice vkDevice) {
        if (device != null && device.vk.address() == vkDevice.address()) {
            device.waitIdleFrames();
            for (Swapchain swapchain : swapchains.values()) {
                if (swapchain.views != null && swapchain.device.address() == vkDevice.address()) {
                    release(swapchain);
                }
            }
            device.destroy();
            device = null;
            graphicsFamilies = null;
        }
        swapchains.values().removeIf(s -> s.device.address() == vkDevice.address());
    }

    // ---- frame ----

    private void present(VkQueue queue, VkPresentInfoKHR info) {
        Swapchain swapchain = null;
        int image = -1;
        LongBuffer handles = info.pSwapchains();
        for (int i = 0; i < info.swapchainCount(); i++) {
            Swapchain known = swapchains.get(handles.get(i));
            if (known != null) {
                swapchain = known;
                image = info.pImageIndices().get(i);
                break;
            }
        }
        if (swapchain == null) {
            overlay.buildFrame(0, 0); // keep the clock and queues going
            return;
        }
        Integer family = queueFamilies.get(queue.address());
        if (family == null) {
            overlay.buildFrame(0, 0);
            return;
        }
        boolean visible = overlay.buildFrame(swapchain.width, swapchain.height);
        if (!visible && !overlay.replayWanted()) {
            return;
        }
        if (!canDrawOn(queue.getDevice(), family)) {
            return; // a present-only queue family cannot run the overlay's render pass
        }
        if (device == null) {
            device = new Device(queue.getDevice(), family, overlay);
            overlay.setRendererName(device.name);
        } else if (device.vk.address() != queue.getDevice().address() || device.family != family) {
            return; // another device or queue family than the one the overlay set up for
        }
        if (swapchain.views == null) {
            device.prepare(swapchain);
        }
        device.collectFinished();
        recording = swapchain.readable && swapchain.width >= 2 && swapchain.height >= 2
                && device.captureFilter(swapchain.format) >= 0;
        if (!recording && overlay.replayWanted()) {
            overlay.replayFailed(new IllegalStateException("the swapchain cannot be read back"));
        }
        long now = System.currentTimeMillis();
        boolean capture = recording && overlay.replayDue(swapchain.width, swapchain.height);
        if (!visible && !capture) {
            return;
        }
        long waits = info.address() + VkPresentInfoKHR.PWAITSEMAPHORES;
        long count = info.address() + VkPresentInfoKHR.WAITSEMAPHORECOUNT;
        long beforeWaits = MemoryUtil.memGetAddress(waits);
        int beforeCount = MemoryUtil.memGetInt(count);
        if (device.draw(queue, swapchain, image, info, visible ? overlay.batch() : null, capture ? now : 0)) {
            rewrittenInfo = info.address();
            originalWaits = beforeWaits;
            originalWaitCount = beforeCount;
        }
    }

    private void restorePresent(VkPresentInfoKHR info) {
        if (rewrittenInfo == 0 || rewrittenInfo != info.address()) {
            return;
        }
        MemoryUtil.memPutAddress(info.address() + VkPresentInfoKHR.PWAITSEMAPHORES, originalWaits);
        MemoryUtil.memPutInt(info.address() + VkPresentInfoKHR.WAITSEMAPHORECOUNT, originalWaitCount);
        rewrittenInfo = 0;
    }

    private boolean canDrawOn(VkDevice vkDevice, int family) {
        if (graphicsFamilies == null) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer count = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(vkDevice.getPhysicalDevice(), count, null);
                VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.malloc(count.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(vkDevice.getPhysicalDevice(), count, families);
                graphicsFamilies = new boolean[count.get(0)];
                for (int i = 0; i < graphicsFamilies.length; i++) {
                    graphicsFamilies[i] = (families.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
                }
            }
        }
        boolean ok = family < graphicsFamilies.length && graphicsFamilies[family];
        if (!ok && !warnedFamily) {
            warnedFamily = true;
            Overlay.log("the game presents on a queue without graphics support; the overlay is not drawn");
        }
        return ok;
    }

    private boolean warnedFamily;

    // ---- per device state ----

    private static void check(int result, String what) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(what + " failed: VkResult " + result);
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = VkRenderer.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IllegalStateException("missing " + name);
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("could not read " + name, e);
        }
    }

    /** A buffer in host-visible, coherent memory, mapped for its whole life. */
    private static final class HostBuffer {
        final long buffer;
        final long memory;
        final long size;
        final ByteBuffer mapped;

        HostBuffer(Device device, long size, int usage) {
            this(device, size, usage, false);
        }

        /** {@code readBack}: the host reads it, so cached memory is preferred where there is some. */
        HostBuffer(Device device, long size, int usage, boolean readBack) {
            this.size = size;
            VkDevice d = device.vk;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                        .size(size)
                        .usage(usage)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateBuffer(d, info, null, out), "vkCreateBuffer");
                buffer = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetBufferMemoryRequirements(d, buffer, requirements);
                int visible = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                memory = readBack
                        ? device.allocate(requirements, visible | VK_MEMORY_PROPERTY_HOST_CACHED_BIT, visible)
                        : device.allocate(requirements, visible, 0);
                check(vkBindBufferMemory(d, buffer, memory, 0), "vkBindBufferMemory");
                PointerBuffer pointer = stack.mallocPointer(1);
                check(vkMapMemory(d, memory, 0, size, 0, pointer), "vkMapMemory");
                mapped = MemoryUtil.memByteBuffer(pointer.get(0), (int) size);
            }
        }

        void destroy(VkDevice d) {
            vkUnmapMemory(d, memory);
            vkDestroyBuffer(d, buffer, null);
            vkFreeMemory(d, memory, null);
        }
    }

    /** Everything the overlay creates on the game's device, plus the swapchain-specific pieces. */
    private static final class Device {
        final VkDevice vk;
        final int family;
        final String name;
        private final Overlay overlay;
        private final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        private final long commandPool;
        private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES];
        private final long[] fences = new long[FRAMES];
        /**
         * Whether a slot's fence belongs to a submission. A fence that was reset for a submit that then
         * failed is never signalled, so waiting on it would hang forever.
         */
        private final boolean[] submitted = new boolean[FRAMES];
        /** One staging copy of the atlas per slot, so an upload never waits for the GPU. */
        private final HostBuffer[] staging = new HostBuffer[FRAMES];
        private final HostBuffer[] vertices = new HostBuffer[FRAMES];
        private final HostBuffer indices;
        private final long atlasImage;
        private final long atlasMemory;
        private final long atlasView;
        private final long sampler;
        private final long setLayout;
        private final long pipelineLayout;
        private final long descriptorPool;
        private final long descriptorSet;
        private final long vertexShader;
        private final long fragmentShader;
        /** Render pass and pipeline per swapchain format. */
        private final Map<Integer, long[]> passes = new HashMap<>();
        private boolean atlasReady;
        private long frame;

        /** The batch's image (the browser's page, BGRA); made for its first frame, remade when its size changes. */
        private long pageImage;
        private long pageMemory;
        private long pageView;
        private int pageWidth;
        private int pageHeight;
        private long pageVersion = -1;
        private boolean pageReady;
        private final HostBuffer[] pageStaging = new HostBuffer[FRAMES];

        /** Replay: the game's frame scaled down, then read into the slot's buffer. */
        private long captureImage;
        private long captureMemory;
        private int captureWidth;
        private int captureHeight;
        private int captureImageFormat;
        /** Per swapchain format: the blit filter for scaling it down, or -1 when it cannot be blitted. */
        private final Map<Integer, Integer> captureFilters = new HashMap<>();
        private final HostBuffer[] readback = new HostBuffer[FRAMES];
        /** When the slot's read-back frame was taken, 0 when the slot holds none, and its size. */
        private final long[] readbackTime = new long[FRAMES];
        private final int[] readbackWidth = new int[FRAMES];
        private final int[] readbackHeight = new int[FRAMES];

        Device(VkDevice vk, int family, Overlay overlay) {
            this.vk = vk;
            this.family = family;
            this.overlay = overlay;
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.malloc(stack);
                vkGetPhysicalDeviceProperties(vk.getPhysicalDevice(), properties);
                name = properties.deviceNameString() + " · Vulkan " + VK_VERSION_MAJOR(properties.apiVersion())
                        + "." + VK_VERSION_MINOR(properties.apiVersion());
                vkGetPhysicalDeviceMemoryProperties(vk.getPhysicalDevice(), memoryProperties);

                LongBuffer out = stack.mallocLong(1);
                VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO)
                        .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                        .queueFamilyIndex(family);
                check(vkCreateCommandPool(vk, poolInfo, null, out), "vkCreateCommandPool");
                commandPool = out.get(0);
                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO)
                        .commandPool(commandPool)
                        .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                        .commandBufferCount(FRAMES);
                PointerBuffer buffers = stack.mallocPointer(FRAMES);
                check(vkAllocateCommandBuffers(vk, allocInfo, buffers), "vkAllocateCommandBuffers");
                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO)
                        .flags(VK_FENCE_CREATE_SIGNALED_BIT);
                for (int i = 0; i < FRAMES; i++) {
                    commands[i] = new VkCommandBuffer(buffers.get(i), vk);
                    check(vkCreateFence(vk, fenceInfo, null, out), "vkCreateFence");
                    fences[i] = out.get(0);
                }

                indices = new HostBuffer(this, (long) Batch.MAX_QUADS * 6 * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
                for (int i = 0; i < Batch.MAX_QUADS; i++) {
                    int v = i * 4;
                    indices.mapped.putInt(v).putInt(v + 1).putInt(v + 2).putInt(v + 2).putInt(v + 3).putInt(v);
                }

                long[] atlas = image(VK_FORMAT_R8_UNORM, Batch.ATLAS, Batch.ATLAS,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                atlasImage = atlas[0];
                atlasMemory = atlas[1];
                atlasView = view(stack, atlasImage, VK_FORMAT_R8_UNORM);

                VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                        .magFilter(VK_FILTER_LINEAR)
                        .minFilter(VK_FILTER_LINEAR)
                        .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                        .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                        .maxLod(0);
                check(vkCreateSampler(vk, samplerInfo, null, out), "vkCreateSampler");
                sampler = out.get(0);

                VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(2, stack);
                for (int i = 0; i < 2; i++) {
                    binding.get(i)
                            .binding(i)
                            .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                            .descriptorCount(1)
                            .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
                }
                VkDescriptorSetLayoutCreateInfo layoutInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                        .pBindings(binding);
                check(vkCreateDescriptorSetLayout(vk, layoutInfo, null, out), "vkCreateDescriptorSetLayout");
                setLayout = out.get(0);

                VkPushConstantRange.Buffer push = VkPushConstantRange.calloc(1, stack);
                push.get(0).stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT).offset(0).size(16);
                VkPipelineLayoutCreateInfo pipelineLayoutInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                        .pSetLayouts(stack.longs(setLayout))
                        .pPushConstantRanges(push);
                check(vkCreatePipelineLayout(vk, pipelineLayoutInfo, null, out), "vkCreatePipelineLayout");
                pipelineLayout = out.get(0);

                VkDescriptorPoolSize.Buffer poolSize = VkDescriptorPoolSize.calloc(1, stack);
                poolSize.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(2);
                VkDescriptorPoolCreateInfo descriptorPoolInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                        .maxSets(1)
                        .pPoolSizes(poolSize);
                check(vkCreateDescriptorPool(vk, descriptorPoolInfo, null, out), "vkCreateDescriptorPool");
                descriptorPool = out.get(0);
                VkDescriptorSetAllocateInfo setInfo = VkDescriptorSetAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                        .descriptorPool(descriptorPool)
                        .pSetLayouts(stack.longs(setLayout));
                check(vkAllocateDescriptorSets(vk, setInfo, out), "vkAllocateDescriptorSets");
                descriptorSet = out.get(0);
                bind(0, atlasView);
                // The image binding must hold something valid before the first page arrives.
                bind(1, atlasView);

                vertexShader = shader(stack, "overlay.vert.spv");
                fragmentShader = shader(stack, "overlay.frag.spv");
            }
        }

        long allocate(VkMemoryRequirements requirements, int wanted, int fallback) {
            int type = memoryType(requirements.memoryTypeBits(), wanted);
            if (type < 0) {
                type = memoryType(requirements.memoryTypeBits(), fallback);
            }
            if (type < 0) {
                throw new IllegalStateException("no suitable Vulkan memory type");
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkMemoryAllocateInfo info = VkMemoryAllocateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                        .allocationSize(requirements.size())
                        .memoryTypeIndex(type);
                LongBuffer out = stack.mallocLong(1);
                check(vkAllocateMemory(vk, info, null, out), "vkAllocateMemory");
                return out.get(0);
            }
        }

        private int memoryType(int bits, int flags) {
            for (int i = 0; i < memoryProperties.memoryTypeCount(); i++) {
                if ((bits & (1 << i)) != 0 && (memoryProperties.memoryTypes(i).propertyFlags() & flags) == flags) {
                    return i;
                }
            }
            return -1;
        }

        private long view(MemoryStack stack, long image, int format) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                    .image(image)
                    .viewType(VK_IMAGE_VIEW_TYPE_2D)
                    .format(format);
            info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateImageView(vk, info, null, out), "vkCreateImageView");
            return out.get(0);
        }

        /** {image, memory} for a 2D image in device-local memory. */
        private long[] image(int format, int width, int height, int usage) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkImageCreateInfo info = VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(format)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(usage)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                info.extent().set(width, height, 1);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateImage(vk, info, null, out), "vkCreateImage");
                long image = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(vk, image, requirements);
                long memory = 0;
                try {
                    memory = allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
                    check(vkBindImageMemory(vk, image, memory, 0), "vkBindImageMemory");
                } catch (RuntimeException e) {
                    vkDestroyImage(vk, image, null);
                    if (memory != 0) {
                        vkFreeMemory(vk, memory, null);
                    }
                    throw e;
                }
                return new long[] {image, memory};
            }
        }

        /** Points a sampler binding of the descriptor set at a view; the set must not be in use by the GPU. */
        private void bind(int binding, long view) {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkDescriptorImageInfo.Buffer imageDescriptor = VkDescriptorImageInfo.calloc(1, stack);
                imageDescriptor.get(0)
                        .sampler(sampler)
                        .imageView(view)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(binding)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imageDescriptor);
                vkUpdateDescriptorSets(vk, write, null);
            }
        }

        private static void barrier(MemoryStack stack, VkCommandBuffer cmd, long image, int srcStage, int dstStage,
                int srcAccess, int dstAccess, int oldLayout, int newLayout) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(srcAccess)
                    .dstAccessMask(dstAccess)
                    .oldLayout(oldLayout)
                    .newLayout(newLayout)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(image);
            barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, srcStage, dstStage, 0, null, null, barrier);
        }

        private long shader(MemoryStack stack, String resource) {
            byte[] code = resource(resource);
            ByteBuffer buffer = stack.malloc(code.length);
            buffer.put(code).flip();
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                    .pCode(buffer);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateShaderModule(vk, info, null, out), "vkCreateShaderModule");
            return out.get(0);
        }

        /** {render pass, pipeline} for a swapchain format. */
        private long[] pass(int format) {
            long[] pass = passes.get(format);
            if (pass != null) {
                return pass;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
                // Load what the game drew and hand it back ready to present.
                attachment.get(0)
                        .format(format)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(VK_ATTACHMENT_LOAD_OP_LOAD)
                        .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR)
                        .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
                VkAttachmentReference.Buffer reference = VkAttachmentReference.calloc(1, stack);
                reference.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
                subpass.get(0)
                        .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                        .colorAttachmentCount(1)
                        .pColorAttachments(reference);
                // The game's last write to the image may be a draw or a copy (blit into the swapchain).
                VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
                dependency.get(0)
                        .srcSubpass(VK_SUBPASS_EXTERNAL)
                        .dstSubpass(0)
                        .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT)
                        .srcAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT)
                        .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                        .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
                VkRenderPassCreateInfo passInfo = VkRenderPassCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO)
                        .pAttachments(attachment)
                        .pSubpasses(subpass)
                        .pDependencies(dependency);
                LongBuffer out = stack.mallocLong(1);
                check(vkCreateRenderPass(vk, passInfo, null, out), "vkCreateRenderPass");
                long renderPass = out.get(0);

                ByteBuffer main = stack.UTF8("main");
                VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
                stages.get(0).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexShader).pName(main);
                stages.get(1).sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                        .stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentShader).pName(main);

                VkVertexInputBindingDescription.Buffer vertexBinding = VkVertexInputBindingDescription.calloc(1, stack);
                vertexBinding.get(0).binding(0).stride(Batch.STRIDE).inputRate(VK_VERTEX_INPUT_RATE_VERTEX);
                VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(4, stack);
                attributes.get(0).location(0).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(0);
                attributes.get(1).location(1).binding(0).format(VK_FORMAT_R32G32_SFLOAT).offset(8);
                attributes.get(2).location(2).binding(0).format(VK_FORMAT_R32G32B32A32_SFLOAT).offset(16);
                attributes.get(3).location(3).binding(0).format(VK_FORMAT_R8G8B8A8_UNORM).offset(32);
                VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO)
                        .pVertexBindingDescriptions(vertexBinding)
                        .pVertexAttributeDescriptions(attributes);
                VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                        .topology(VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST);
                VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                        .viewportCount(1)
                        .scissorCount(1);
                VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                        .polygonMode(VK_POLYGON_MODE_FILL)
                        .cullMode(VK_CULL_MODE_NONE)
                        .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE)
                        .lineWidth(1);
                VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                        .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
                VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                        VkPipelineColorBlendAttachmentState.calloc(1, stack);
                // Same blending as the GL renderer; destination alpha stays as the game left it.
                blendAttachment.get(0)
                        .blendEnable(true)
                        .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                        .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                        .colorBlendOp(VK_BLEND_OP_ADD)
                        .srcAlphaBlendFactor(VK_BLEND_FACTOR_ZERO)
                        .dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
                        .alphaBlendOp(VK_BLEND_OP_ADD)
                        .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT
                                | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
                VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                        .pAttachments(blendAttachment);
                VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                        .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));

                VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack);
                pipelineInfo.get(0)
                        .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                        .pStages(stages)
                        .pVertexInputState(vertexInput)
                        .pInputAssemblyState(assembly)
                        .pViewportState(viewport)
                        .pRasterizationState(raster)
                        .pMultisampleState(multisample)
                        .pColorBlendState(blend)
                        .pDynamicState(dynamic)
                        .layout(pipelineLayout)
                        .renderPass(renderPass)
                        .subpass(0);
                check(vkCreateGraphicsPipelines(vk, VK_NULL_HANDLE, pipelineInfo, null, out), "vkCreateGraphicsPipelines");
                pass = new long[] {renderPass, out.get(0)};
                passes.put(format, pass);
                return pass;
            }
        }

        /** Views, framebuffers and semaphores for the images of a swapchain seen for the first time. */
        void prepare(Swapchain swapchain) {
            long renderPass = pass(swapchain.format)[0];
            try (MemoryStack stack = MemoryStack.stackPush()) {
                java.nio.IntBuffer count = stack.mallocInt(1);
                check(vkGetSwapchainImagesKHR(vk, swapchain.handle, count, null), "vkGetSwapchainImagesKHR");
                LongBuffer images = stack.mallocLong(count.get(0));
                check(vkGetSwapchainImagesKHR(vk, swapchain.handle, count, images), "vkGetSwapchainImagesKHR");
                int n = count.get(0);
                swapchain.images = new long[n];
                long[] views = new long[n];
                swapchain.framebuffers = new long[n];
                swapchain.done = new long[n];
                swapchain.waits = new LongBuffer[n];
                LongBuffer out = stack.mallocLong(1);
                VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);
                for (int i = 0; i < n; i++) {
                    swapchain.images[i] = images.get(i);
                    views[i] = view(stack, images.get(i), swapchain.format);
                    VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.calloc(stack)
                            .sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO)
                            .renderPass(renderPass)
                            .pAttachments(stack.longs(views[i]))
                            .width(swapchain.width)
                            .height(swapchain.height)
                            .layers(1);
                    check(vkCreateFramebuffer(vk, framebufferInfo, null, out), "vkCreateFramebuffer");
                    swapchain.framebuffers[i] = out.get(0);
                    check(vkCreateSemaphore(vk, semaphoreInfo, null, out), "vkCreateSemaphore");
                    swapchain.done[i] = out.get(0);
                    swapchain.waits[i] = MemoryUtil.memAllocLong(1);
                }
                swapchain.views = views;
            }
        }

        /** Waits until none of the overlay's frames is still running on the GPU. */
        void waitIdleFrames() {
            for (int i = 0; i < FRAMES; i++) {
                waitSlot(i);
            }
        }

        private void waitSlot(int slot) {
            if (!submitted[slot]) {
                return;
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkWaitForFences(vk, stack.longs(fences[slot]), true, Long.MAX_VALUE), "vkWaitForFences");
            }
        }

        /**
         * Records and submits work on one swapchain image: a read-back for the replay buffer when
         * {@code captureTime} is not 0, then the overlay unless {@code batch} is null. True once the
         * present was rewired.
         */
        boolean draw(VkQueue queue, Swapchain swapchain, int image, VkPresentInfoKHR present, Batch batch, long captureTime) {
            int slot = (int) (frame++ % FRAMES);
            waitSlot(slot);
            collect(slot);
            if (captureTime != 0 && !prepareCapture(swapchain, slot)) {
                captureTime = 0;
                if (batch == null) {
                    return false;
                }
            }
            try (MemoryStack stack = MemoryStack.stackPush()) {
                VkCommandBuffer cmd = commands[slot];
                check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

                if (captureTime != 0) {
                    capture(stack, cmd, swapchain, image, slot, captureTime);
                }
                if (batch != null) {
                    record(stack, cmd, swapchain, image, batch, slot);
                }
                check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");

                // Run after whatever the present was going to wait for; the present waits for us instead.
                int waits = present.waitSemaphoreCount();
                VkSubmitInfo submit = VkSubmitInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_SUBMIT_INFO)
                        .pCommandBuffers(stack.pointers(cmd))
                        .pSignalSemaphores(stack.longs(swapchain.done[image]));
                if (waits > 0) {
                    java.nio.IntBuffer stages = stack.mallocInt(waits);
                    for (int i = 0; i < waits; i++) {
                        // Transfer too: the replay copy reads the image before the render pass.
                        stages.put(i, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT);
                    }
                    submit.waitSemaphoreCount(waits)
                            .pWaitSemaphores(present.pWaitSemaphores())
                            .pWaitDstStageMask(stages);
                }
                submitted[slot] = false;
                check(vkResetFences(vk, stack.longs(fences[slot])), "vkResetFences");
                check(vkQueueSubmit(queue, submit, fences[slot]), "vkQueueSubmit");
                submitted[slot] = true;
                // Persistent memory: the present reads it after this hook has returned.
                swapchain.waits[image].put(0, swapchain.done[image]);
                present.pWaitSemaphores(swapchain.waits[image]);
                return true;
            }
        }

        /** Uploads what changed and records the render pass that draws the batch over the image. */
        private void record(MemoryStack stack, VkCommandBuffer cmd, Swapchain swapchain, int image, Batch batch, int slot) {
            long[] pass = pass(swapchain.format);
            int bytes = batch.vertices.limit();
            if (vertices[slot] == null || vertices[slot].size < bytes) {
                if (vertices[slot] != null) {
                    vertices[slot].destroy(vk);
                }
                vertices[slot] = new HostBuffer(this, Math.max(bytes, 1 << 18), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            }
            MemoryUtil.memCopy(memAddress(batch.vertices, 0), memAddress(vertices[slot].mapped, 0), bytes);

            if (batch.atlasDirty() || !atlasReady) {
                uploadAtlas(stack, cmd, batch, slot);
            }
            if (batch.image != null && batch.imageVersion != pageVersion) {
                uploadPage(stack, cmd, batch, slot);
            }
            VkClearValue.Buffer noClear = VkClearValue.calloc(1, stack);
            VkRenderPassBeginInfo passBegin = VkRenderPassBeginInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO)
                    .renderPass(pass[0])
                    .framebuffer(swapchain.framebuffers[image])
                    .pClearValues(noClear);
            passBegin.renderArea().extent().set(swapchain.width, swapchain.height);
            vkCmdBeginRenderPass(cmd, passBegin, VK_SUBPASS_CONTENTS_INLINE);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pass[1]);
            VkViewport.Buffer viewport = VkViewport.calloc(1, stack);
            viewport.get(0).width(swapchain.width).height(swapchain.height).maxDepth(1);
            vkCmdSetViewport(cmd, 0, viewport);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(vertices[slot].buffer), stack.longs(0));
            vkCmdBindIndexBuffer(cmd, indices.buffer, 0, VK_INDEX_TYPE_UINT32);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0,
                    stack.longs(descriptorSet), null);
            ByteBuffer constants = stack.malloc(16);
            constants.putFloat(0, swapchain.width).putFloat(4, swapchain.height)
                    .putFloat(8, srgb(swapchain.format) ? 1 : 0).putFloat(12, 0);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT, 0, constants);
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            for (Batch.Draw d : batch.draws) {
                int x0 = 0;
                int y0 = 0;
                int x1 = swapchain.width;
                int y1 = swapchain.height;
                if (d.clip != null) {
                    x0 = Math.max(0, Math.min(swapchain.width, d.clip[0]));
                    y0 = Math.max(0, Math.min(swapchain.height, d.clip[1]));
                    x1 = Math.max(x0, Math.min(swapchain.width, d.clip[2]));
                    y1 = Math.max(y0, Math.min(swapchain.height, d.clip[3]));
                }
                if (x1 == x0 || y1 == y0) {
                    continue;
                }
                scissor.get(0).offset().set(x0, y0);
                scissor.get(0).extent().set(x1 - x0, y1 - y0);
                vkCmdSetScissor(cmd, 0, scissor);
                vkCmdDrawIndexed(cmd, d.quads * 6, 1, d.firstQuad * 6, 0, 0);
            }
            vkCmdEndRenderPass(cmd);
        }

        private void uploadAtlas(MemoryStack stack, VkCommandBuffer cmd, Batch batch, int slot) {
            // This slot's staging buffer is free: its last frame finished before draw went on.
            if (staging[slot] == null) {
                staging[slot] = new HostBuffer(this, (long) Batch.ATLAS * Batch.ATLAS, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            }
            HostBuffer stage = staging[slot];
            int x0 = atlasReady ? batch.dirty[0] : 0;
            int y0 = atlasReady ? batch.dirty[1] : 0;
            int x1 = atlasReady ? batch.dirty[2] : Batch.ATLAS;
            int y1 = atlasReady ? batch.dirty[3] : Batch.ATLAS;
            batch.atlasUploaded();
            // Only the changed rows; the copy below reads just the dirty rectangle within them.
            long offset = (long) y0 * Batch.ATLAS;
            MemoryUtil.memCopy(memAddress(batch.atlas, 0) + offset, memAddress(stage.mapped, 0) + offset,
                    (long) (y1 - y0) * Batch.ATLAS);

            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                    .srcAccessMask(atlasReady ? VK_ACCESS_SHADER_READ_BIT : 0)
                    .dstAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .oldLayout(atlasReady ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED)
                    .newLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(atlasImage);
            barrier.get(0).subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0,
                    null, null, barrier);

            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0)
                    .bufferOffset((long) y0 * Batch.ATLAS + x0)
                    .bufferRowLength(Batch.ATLAS)
                    .bufferImageHeight(Batch.ATLAS);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageOffset().set(x0, y0, 0);
            region.get(0).imageExtent().set(x1 - x0, y1 - y0, 1);
            vkCmdCopyBufferToImage(cmd, stage.buffer, atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            barrier.get(0)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barrier);
            atlasReady = true;
        }

        /** Copies the batch's image (the browser's page) into the image binding, remaking it on a new size. */
        private void uploadPage(MemoryStack stack, VkCommandBuffer cmd, Batch batch, int slot) {
            int w = batch.imageWidth;
            int h = batch.imageHeight;
            if (w <= 0 || h <= 0) {
                return;
            }
            if (w != pageWidth || h != pageHeight) {
                // Frames still on the GPU sample the old image through the descriptor set being rewritten.
                waitIdleFrames();
                destroyPage();
                long[] page = image(VK_FORMAT_B8G8R8A8_UNORM, w, h, VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
                pageImage = page[0];
                pageMemory = page[1];
                pageView = view(stack, pageImage, VK_FORMAT_B8G8R8A8_UNORM);
                pageWidth = w;
                pageHeight = h;
                pageReady = false;
                bind(1, pageView);
            }
            long bytes = (long) w * h * 4;
            // This slot's staging buffer is free: its last frame finished before draw went on.
            if (pageStaging[slot] == null || pageStaging[slot].size < bytes) {
                if (pageStaging[slot] != null) {
                    pageStaging[slot].destroy(vk);
                }
                pageStaging[slot] = new HostBuffer(this, bytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
            }
            MemoryUtil.memCopy(memAddress(batch.image, 0), memAddress(pageStaging[slot].mapped, 0), bytes);

            barrier(stack, cmd, pageImage, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    pageReady ? VK_ACCESS_SHADER_READ_BIT : 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                    pageReady ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyBufferToImage(cmd, pageStaging[slot].buffer, pageImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
            barrier(stack, cmd, pageImage, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            pageReady = true;
            pageVersion = batch.imageVersion;
        }

        private void destroyPage() {
            if (pageImage == 0) {
                return;
            }
            vkDestroyImageView(vk, pageView, null);
            vkDestroyImage(vk, pageImage, null);
            vkFreeMemory(vk, pageMemory, null);
            pageImage = pageView = pageMemory = 0;
            pageWidth = pageHeight = 0;
        }

        /**
         * The filter for scaling a swapchain format down into the replay image, or -1 when the format
         * cannot be blitted from (or the replay image's format blitted to).
         */
        int captureFilter(int format) {
            Integer filter = captureFilters.get(format);
            if (filter == null) {
                try (MemoryStack stack = MemoryStack.stackPush()) {
                    VkFormatProperties source = VkFormatProperties.malloc(stack);
                    vkGetPhysicalDeviceFormatProperties(vk.getPhysicalDevice(), format, source);
                    VkFormatProperties target = VkFormatProperties.malloc(stack);
                    vkGetPhysicalDeviceFormatProperties(vk.getPhysicalDevice(), captureFormat(format), target);
                    int from = source.optimalTilingFeatures();
                    if ((from & VK_FORMAT_FEATURE_BLIT_SRC_BIT) == 0
                            || (target.optimalTilingFeatures() & VK_FORMAT_FEATURE_BLIT_DST_BIT) == 0) {
                        filter = -1;
                        Overlay.log("swapchain format " + format + " cannot be blitted; the replay buffer is off");
                    } else {
                        filter = (from & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT) != 0 ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
                    }
                }
                captureFilters.put(format, filter);
            }
            return filter;
        }

        /** RGBA, sRGB when the swapchain is, so the blit keeps the bytes the game shows. */
        private static int captureFormat(int format) {
            return srgb(format) ? VK_FORMAT_R8G8B8A8_SRGB : VK_FORMAT_R8G8B8A8_UNORM;
        }

        /**
         * Makes the capture image and this slot's read-back buffer for the swapchain's size. A failure
         * turns the replay buffer off, as on OpenGL, and leaves the overlay drawing.
         */
        private boolean prepareCapture(Swapchain swapchain, int slot) {
            int w = overlay.recorder().width();
            int h = overlay.recorder().height();
            int format = captureFormat(swapchain.format);
            try {
                if (w != captureWidth || h != captureHeight || format != captureImageFormat) {
                    // Earlier frames may still be copying out of the old image.
                    waitIdleFrames();
                    destroyCapture();
                    long[] target = image(format, w, h, VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
                    captureImage = target[0];
                    captureMemory = target[1];
                    captureWidth = w;
                    captureHeight = h;
                    captureImageFormat = format;
                }
                long bytes = (long) w * h * 4;
                if (readback[slot] == null || readback[slot].size < bytes) {
                    if (readback[slot] != null) {
                        readback[slot].destroy(vk);
                        readback[slot] = null;
                    }
                    readback[slot] = new HostBuffer(this, bytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
                }
                return true;
            } catch (RuntimeException e) {
                overlay.replayFailed(e);
                return false;
            }
        }

        /**
         * Records a read-back of the game's frame, before the overlay draws on it: scaled to the
         * recorder's size as top-down RGBA into this slot's buffer, collected once the slot's fence
         * signals and converted to NV12 by the recorder (a Java loop, which avoids another Vulkan pipeline).
         */
        private void capture(MemoryStack stack, VkCommandBuffer cmd, Swapchain swapchain, int image, int slot, long time) {
            int w = captureWidth;
            int h = captureHeight;
            long source = swapchain.images[image];

            barrier(stack, cmd, source,
                    VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            // The previous frame's copy out of the capture image runs earlier on the queue; its contents go.
            barrier(stack, cmd, captureImage, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    0, VK_ACCESS_TRANSFER_WRITE_BIT, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkImageBlit.Buffer blit = VkImageBlit.calloc(1, stack);
            blit.get(0).srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).srcOffsets(1).set(swapchain.width, swapchain.height, 1);
            blit.get(0).dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            blit.get(0).dstOffsets(1).set(w, h, 1);
            vkCmdBlitImage(cmd, source, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, captureImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    blit, captureFilter(swapchain.format));
            // Hand the game's image back as it was, ready for the render pass or the present.
            barrier(stack, cmd, source, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
                    0, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            barrier(stack, cmd, captureImage, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                    VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack);
            region.get(0).imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
            region.get(0).imageExtent().set(w, h, 1);
            vkCmdCopyImageToBuffer(cmd, captureImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, readback[slot].buffer, region);
            VkMemoryBarrier.Buffer hostRead = VkMemoryBarrier.calloc(1, stack);
            hostRead.get(0)
                    .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_HOST_BIT, 0, hostRead, null, null);
            readbackTime[slot] = time;
            readbackWidth[slot] = w;
            readbackHeight[slot] = h;
        }

        /**
         * Hands over every read-back whose frame already finished, without waiting, so frames do not sit in
         * their slot until it is reused (with the overlay hidden that is only on the next captures).
         */
        void collectFinished() {
            for (int slot = 0; slot < FRAMES; slot++) {
                if (readbackTime[slot] != 0 && submitted[slot] && vkGetFenceStatus(vk, fences[slot]) == VK_SUCCESS) {
                    collect(slot);
                }
            }
        }

        /** Hands the slot's finished read-back, if any, to the replay buffer; the slot's fence has signalled. */
        private void collect(int slot) {
            long time = readbackTime[slot];
            if (time == 0) {
                return;
            }
            readbackTime[slot] = 0;
            if (!submitted[slot]) {
                return; // the submit that would have filled it failed
            }
            int bytes = readbackWidth[slot] * readbackHeight[slot] * 4;
            Recorder recorder = overlay.recorder();
            if (readbackWidth[slot] != recorder.width() || readbackHeight[slot] != recorder.height()) {
                return; // from a recording that has ended
            }
            Recorder.Frame frame = recorder.acquire(true);
            if (frame != null) {
                // The recorder converts it to NV12 on its writer thread.
                ByteBuffer pixels = readback[slot].mapped.duplicate();
                pixels.limit(bytes);
                pixels.get(frame.data, 0, bytes);
                recorder.submit(frame);
            }
        }

        private void destroyCapture() {
            if (captureImage == 0) {
                return;
            }
            vkDestroyImage(vk, captureImage, null);
            vkFreeMemory(vk, captureMemory, null);
            captureImage = captureMemory = 0;
            captureWidth = captureHeight = 0;
        }

        private static boolean srgb(int format) {
            switch (format) {
                case VK_FORMAT_B8G8R8A8_SRGB:
                case VK_FORMAT_R8G8B8A8_SRGB:
                case VK_FORMAT_A8B8G8R8_SRGB_PACK32:
                    return true;
                default:
                    return false;
            }
        }

        void destroy() {
            for (long[] pass : passes.values()) {
                vkDestroyPipeline(vk, pass[1], null);
                vkDestroyRenderPass(vk, pass[0], null);
            }
            vkDestroyShaderModule(vk, vertexShader, null);
            vkDestroyShaderModule(vk, fragmentShader, null);
            vkDestroyDescriptorPool(vk, descriptorPool, null);
            vkDestroyPipelineLayout(vk, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(vk, setLayout, null);
            vkDestroySampler(vk, sampler, null);
            destroyPage();
            destroyCapture();
            vkDestroyImageView(vk, atlasView, null);
            vkDestroyImage(vk, atlasImage, null);
            vkFreeMemory(vk, atlasMemory, null);
            indices.destroy(vk);
            for (int i = 0; i < FRAMES; i++) {
                if (staging[i] != null) {
                    staging[i].destroy(vk);
                }
                if (vertices[i] != null) {
                    vertices[i].destroy(vk);
                }
                if (pageStaging[i] != null) {
                    pageStaging[i].destroy(vk);
                }
                if (readback[i] != null) {
                    readback[i].destroy(vk);
                }
                vkDestroyFence(vk, fences[i], null);
            }
            vkDestroyCommandPool(vk, commandPool, null);
            memoryProperties.free();
        }
    }
}
