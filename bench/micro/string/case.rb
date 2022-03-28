if RUBY_ENGINE == 'truffleruby'
  str = "Foo Bar Baz Foo Bar Baz Foo Bar Baz"
  benchmark "swapcase!" do
    str.swapcase!
  end

  str2 = "Foo Bar Baz Foo Bar Baz Foo Bar Baz " * 1000
  benchmark "long swapcase!" do
    str2.swapcase!
  end
end
