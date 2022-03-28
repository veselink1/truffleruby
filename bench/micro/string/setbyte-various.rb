# Tests 32 different string sizes
(64...2048).step(64) do |n|
    buffer = "0" * n
    benchmark n.to_s do
        buffer.size.times do |index|
            buffer[index] = (index % 20).chr
        end
    end
end
