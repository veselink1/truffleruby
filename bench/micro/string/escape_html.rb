require 'cgi'

dev_null = File.open('/dev/null', 'w')

benchmark "escapeHTML" do
    string = CGI.escapeHTML('<html> <head> <meta charset="UTF-8"> <meta description="No description."> </head> <body> </body> </html>')
    # Truffle::Ropes.debug_print_rope(string)
    dev_null.write string
end
