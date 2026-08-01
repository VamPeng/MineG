package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"strings"
	"time"

	"github.com/vampeng/mineg/service/internal/platform/objectstore"
)

func main() {
	expiration, err := time.Parse(time.RFC3339, strings.TrimSpace(os.Getenv("MINEG_OSS_STS_EXPIRATION")))
	if err != nil {
		log.Fatal("MINEG_OSS_STS_EXPIRATION must contain the AssumeRole RFC3339 expiration")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 45*time.Second)
	defer cancel()
	err = objectstore.CheckLocalOSSAccess(ctx, objectstore.OSSProfileConfig{
		Region: os.Getenv("MINEG_OSS_REGION"), Bucket: os.Getenv("MINEG_OSS_BUCKET"),
		PublicEndpoint: os.Getenv("MINEG_OSS_PUBLIC_ORIGIN"), AccessKeyID: os.Getenv("MINEG_OSS_ACCESS_KEY_ID"),
		AccessKeySecret: os.Getenv("MINEG_OSS_ACCESS_KEY_SECRET"), SecurityToken: os.Getenv("MINEG_OSS_SECURITY_TOKEN"),
		CredentialsExpiration: expiration,
	})
	if err != nil {
		log.Fatalf("local OSS permission check failed: %v", err)
	}
	fmt.Println("Local OSS permission check passed: multipart initiate/upload/list/abort allowed; bucket list, cross-prefix read, and object delete denied.")
}
