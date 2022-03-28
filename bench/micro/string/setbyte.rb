benchmark "small_buffer[]= alloc" do
    small_buffer = "0" * 280 # Tweet-size buffer
    small_buffer.size.times do |index|
        small_buffer[index] = (index % 20).chr
    end
end

small_buffer = "0" * 280 # Tweet-size buffer
benchmark "small_buffer[]= no-alloc" do
    small_buffer.size.times do |index|
        small_buffer[index] = (index % 20).chr
    end
end

benchmark "large_buffer[]= alloc" do
    large_buffer = "0" * 256 * 1024 # 256 KiB buffer
    large_buffer.size.times do |index|
        large_buffer[index] = (index % 20).chr
    end
end

large_buffer = "0" * 256 * 1024 # 256 KiB buffer
benchmark "large_buffer[]= no-alloc" do
    large_buffer.size.times do |index|
        large_buffer[index] = (index % 20).chr
    end
end

small_buffer = "0" * 280 # Tweet-size buffer
benchmark "small_buffer.setbyte no-alloc" do
    small_buffer.size.times do |index|
        small_buffer.setbyte(index, index % 20)
    end
end

large_buffer = "0" * 256 * 1024 # 256 KiB buffer
benchmark "large_buffer.setbyte no-alloc" do
    large_buffer.size.times do |index|
        large_buffer.setbyte(index, index % 20)
    end
end

benchmark "large_buffer interleaved" do
    small_buffer = "0" * 256 * 1024 # 256 KiB buffer
    (0..small_buffer.size - 2).step(3).times do |index|
        small_buffer[index] = (index % 20).chr
        small_buffer[index + 1] = ((index + 1) % 20).chr
        small_buffer[index + 2] = ((index + 2) % 20).chr
    end
end
