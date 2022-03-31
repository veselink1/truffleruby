require 'benchmark/ips'

puts RUBY_DESCRIPTION

image = '0' * 10_000

Benchmark.ips do |x|

  x.report('alloc-10') do
    image = '0' * 10
    image.size.times do |n|
      image[n] = (n % 100).chr
    end
  end
  x.report('alloc-100') do
    image = '0' * 100
    image.size.times do |n|
      image[n] = (n % 100).chr
    end
  end
  x.report('alloc-1000') do
    image = '0' * 1000
    image.size.times do |n|
      image[n] = (n % 100).chr
    end
  end
  x.report('alloc-10_000') do
    image = '0' * 10_000
    image.size.times do |n|
      image[n] = (n % 100).chr
    end
  end
  x.report('alloc-100_000') do
    image = '0' * 100_000
    image.size.times do |n|
      image[n] = (n % 100).chr
    end
  end

  # Single character replacement
  x.report('alloc-10-1') do
    image = '0' * 10
    image[3] = '1'
  end
  x.report('alloc-100-1') do
    image = '0' * 100
    image[3] = '1'
  end
  x.report('alloc-1000-1') do
    image = '0' * 1000
    image[3] = '1'
  end
  x.report('alloc-10_000-1') do
    image = '0' * 10_000
    image[3] = '1'
  end
  x.report('alloc-100_000-1') do
    image = '0' * 100_000
    image[3] = '1'
  end
  x.report('alloc-1M-1') do
    image = '0' * 1_000_000
    image[3] = '1'
  end
  x.report('alloc-10M-1') do
    image = '0' * 10_000_000
    image[3] = '1'
  end
  x.report('alloc-100M-1') do
    image = '0' * 100_000_000
    image[3] = '1'
  end

end
