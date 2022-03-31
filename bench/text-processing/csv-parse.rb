require 'csv'

benchmark "parse" do
    data = CSV.parse(<<~ROWS, headers: true)
        Name,Department,Salary
        Bob,Engineering,1000
        Jane,Sales,2000
        John,Management,5000
    ROWS
end
