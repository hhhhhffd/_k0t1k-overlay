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
 * a game may only copy into its swapchain images.
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

    VkRenderer(Overlay overlay) {
        this.overlay = overlay;
    }

    @Override
    public void disable() {
        disabled = true;
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
                if (!disabled) {
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
        long[] images;
        long[] views;
        long[] framebuffers;
        /** Signalled by the overlay's draw for each image, waited on by its present. */
        long[] done;
        /** Native one-element arrays handed to the present as its wait semaphores. */
        LongBuffer[] waits;

        Swapchain(VkDevice device, long handle, int format, int width, int height) {
            this.device = device;
            this.handle = handle;
            this.format = format;
            this.width = width;
            this.height = height;
        }
    }

    private Object swapchainCreating(Object[] args) {
        VkSwapchainCreateInfoKHR info = (VkSwapchainCreateInfoKHR) args[1];
        // Every surface supports colour attachment use, so this never makes creation fail.
        info.imageUsage(info.imageUsage() | VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
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
        swapchains.put(handle, new Swapchain(vkDevice, handle, info.imageFormat(), extent.width(), extent.height()));
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
        if (!overlay.buildFrame(swapchain.width, swapchain.height)) {
            return;
        }
        if (device == null) {
            device = new Device(queue.getDevice(), family);
            overlay.setRendererName(device.name);
        } else if (device.vk.address() != queue.getDevice().address() || device.family != family) {
            return; // another device or queue family than the one the overlay set up for
        }
        if (swapchain.views == null) {
            device.prepare(swapchain);
        }
        device.draw(queue, swapchain, image, info, overlay.batch());
    }

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
                memory = device.allocate(requirements,
                        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, 0);
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
        private final VkPhysicalDeviceMemoryProperties memoryProperties = VkPhysicalDeviceMemoryProperties.calloc();
        private final long commandPool;
        private final VkCommandBuffer[] commands = new VkCommandBuffer[FRAMES];
        private final long[] fences = new long[FRAMES];
        private final HostBuffer[] vertices = new HostBuffer[FRAMES];
        private final HostBuffer indices;
        private final HostBuffer staging;
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

        Device(VkDevice vk, int family) {
            this.vk = vk;
            this.family = family;
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
                staging = new HostBuffer(this, (long) Batch.ATLAS * Batch.ATLAS, VK_BUFFER_USAGE_TRANSFER_SRC_BIT);

                VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                        .imageType(VK_IMAGE_TYPE_2D)
                        .format(VK_FORMAT_R8_UNORM)
                        .mipLevels(1)
                        .arrayLayers(1)
                        .samples(VK_SAMPLE_COUNT_1_BIT)
                        .tiling(VK_IMAGE_TILING_OPTIMAL)
                        .usage(VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT)
                        .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                        .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                imageInfo.extent().set(Batch.ATLAS, Batch.ATLAS, 1);
                check(vkCreateImage(vk, imageInfo, null, out), "vkCreateImage");
                atlasImage = out.get(0);
                VkMemoryRequirements requirements = VkMemoryRequirements.malloc(stack);
                vkGetImageMemoryRequirements(vk, atlasImage, requirements);
                atlasMemory = allocate(requirements, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0);
                check(vkBindImageMemory(vk, atlasImage, atlasMemory, 0), "vkBindImageMemory");
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

                VkDescriptorSetLayoutBinding.Buffer binding = VkDescriptorSetLayoutBinding.calloc(1, stack);
                binding.get(0)
                        .binding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
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
                poolSize.get(0).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
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
                VkDescriptorImageInfo.Buffer imageDescriptor = VkDescriptorImageInfo.calloc(1, stack);
                imageDescriptor.get(0)
                        .sampler(sampler)
                        .imageView(atlasView)
                        .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
                write.get(0)
                        .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                        .dstSet(descriptorSet)
                        .dstBinding(0)
                        .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                        .descriptorCount(1)
                        .pImageInfo(imageDescriptor);
                vkUpdateDescriptorSets(vk, write, null);

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
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkWaitForFences(vk, stack.longs(fences), true, Long.MAX_VALUE), "vkWaitForFences");
            }
        }

        void draw(VkQueue queue, Swapchain swapchain, int image, VkPresentInfoKHR present, Batch batch) {
            int slot = (int) (frame++ % FRAMES);
            long[] pass = pass(swapchain.format);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                check(vkWaitForFences(vk, stack.longs(fences[slot]), true, Long.MAX_VALUE), "vkWaitForFences");

                int bytes = batch.vertices.limit();
                if (vertices[slot] == null || vertices[slot].size < bytes) {
                    if (vertices[slot] != null) {
                        vertices[slot].destroy(vk);
                    }
                    vertices[slot] = new HostBuffer(this, Math.max(bytes, 1 << 18), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
                }
                MemoryUtil.memCopy(memAddress(batch.vertices, 0), memAddress(vertices[slot].mapped, 0), bytes);

                VkCommandBuffer cmd = commands[slot];
                check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
                VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.calloc(stack)
                        .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                        .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
                check(vkBeginCommandBuffer(cmd, begin), "vkBeginCommandBuffer");

                if (batch.atlasDirty() || !atlasReady) {
                    uploadAtlas(stack, cmd, batch);
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
                        stages.put(i, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
                    }
                    submit.waitSemaphoreCount(waits)
                            .pWaitSemaphores(present.pWaitSemaphores())
                            .pWaitDstStageMask(stages);
                }
                check(vkResetFences(vk, stack.longs(fences[slot])), "vkResetFences");
                check(vkQueueSubmit(queue, submit, fences[slot]), "vkQueueSubmit");
                // Persistent memory: the present reads it after this hook has returned.
                swapchain.waits[image].put(0, swapchain.done[image]);
                present.pWaitSemaphores(swapchain.waits[image]);
            }
        }

        private void uploadAtlas(MemoryStack stack, VkCommandBuffer cmd, Batch batch) {
            // The staging copy may still be read by a frame in flight.
            waitIdleFrames();
            MemoryUtil.memCopy(memAddress(batch.atlas, 0), memAddress(staging.mapped, 0), (long) Batch.ATLAS * Batch.ATLAS);
            int x0 = atlasReady ? batch.dirty[0] : 0;
            int y0 = atlasReady ? batch.dirty[1] : 0;
            int x1 = atlasReady ? batch.dirty[2] : Batch.ATLAS;
            int y1 = atlasReady ? batch.dirty[3] : Batch.ATLAS;
            batch.atlasUploaded();

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
            vkCmdCopyBufferToImage(cmd, staging.buffer, atlasImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);

            barrier.get(0)
                    .srcAccessMask(VK_ACCESS_TRANSFER_WRITE_BIT)
                    .dstAccessMask(VK_ACCESS_SHADER_READ_BIT)
                    .oldLayout(VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
                    .newLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0,
                    null, null, barrier);
            atlasReady = true;
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
            vkDestroyImageView(vk, atlasView, null);
            vkDestroyImage(vk, atlasImage, null);
            vkFreeMemory(vk, atlasMemory, null);
            staging.destroy(vk);
            indices.destroy(vk);
            for (int i = 0; i < FRAMES; i++) {
                if (vertices[i] != null) {
                    vertices[i].destroy(vk);
                }
                vkDestroyFence(vk, fences[i], null);
            }
            vkDestroyCommandPool(vk, commandPool, null);
            memoryProperties.free();
        }
    }
}
