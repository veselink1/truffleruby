require 'nokogiri'

html = File.read(__dir__ + "/douglas-adams.html")
doc = Nokogiri::HTML(html)

benchmark "nokogiri-select" do
    data_arr = []
    description = doc.css("p").text.split("\n").find{|e| e.length > 0}
    picture = doc.css("td a img").find{|picture| picture.attributes["alt"].value.include?("Douglas adams portrait cropped.jpg")}.attributes["src"].value
end
