# Copyright (c) 2016 Oracle and/or its affiliates. All rights reserved. This
# code is released under a tri EPL/GPL/LGPL license. You can use it,
# redistribute it and/or modify it under the terms of the:
#
# Eclipse Public License version 2.0, or
# GNU General Public License version 2, or
# GNU Lesser General Public License version 2.1.

module BenchmarkInterface
  module Backends
    module Bips

      LONG_ITERATION_THRESHOLD = 0.1 # seconds

      def self.run(benchmark_set, names, options)
        BenchmarkInterface.require_rubygems
        benchmark_interface_original_require 'benchmark/ips'

        unless options['--no-scale']
          max_iteration_time = benchmark_set.benchmarks.map(&:basic_iteration_time).max
          if max_iteration_time > LONG_ITERATION_THRESHOLD
            # Benchmarks that take 0.1 seconds to run will be run for at least 10 seconds (scale by 100)
            long_iterations_time = 10 * (1 + Math.log(max_iteration_time / LONG_ITERATION_THRESHOLD))
            puts "These are long benchmarks - we're increasing warmup and sample time to %d seconds per iteration" % [long_iterations_time]
          end
        end

        ::Benchmark.ips do |x|
          x.iterations = 3

          if long_iterations_time
            x.time = long_iterations_time
            x.warmup = long_iterations_time
          end

          benchmark_set.benchmarks(names).each do |benchmark|
            x.report benchmark.name, &benchmark.block
          end

          x.compare!
        end
      end

    end
  end
end
