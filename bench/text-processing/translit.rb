require_relative 'translit/lib/translit'

cyrilic = "В исторически план кирилицата, заедно с глаголицата, е едната от двете азбуки, използвани при записването на старобългарския книжовен език. Кирилицата е създадена в Преславската книжовна школа към края на IX или началото на X век."

benchmark "two-way-tweet" do
    latin = Translit.convert(cyrilic)
    cyrilic2 = Translit.convert(latin)
end
