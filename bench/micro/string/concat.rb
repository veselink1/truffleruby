

small = 'a' * 100
med = 'a' * 250
large = 'a' * 1000

result = ''

benchmark "core-string-concat-small" do
    result = (small + rand(1..10).to_s).hash
end

benchmark "core-string-concat-med" do
    result = (med + rand(1..10).to_s).hash
end

benchmark "core-string-concat-large" do
    result = (large + rand(1..10).to_s).hash
end
