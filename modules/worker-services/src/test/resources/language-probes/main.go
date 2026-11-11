package main
import (
	"fmt"
	"os"
)
func main() {
	for index, argument := range os.Args {
		fmt.Println(index, argument)
	}
}
