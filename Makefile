.PHONY: build-LiveRegistration build-TrainingPush build-Operations

build-LiveRegistration build-TrainingPush build-Operations:
	mkdir -p "$(ARTIFACTS_DIR)/assets/languages"
	cp assets/languages/en.json assets/languages/ja.json assets/languages/ko.json "$(ARTIFACTS_DIR)/assets/languages/"
	mkdir -p "$(ARTIFACTS_DIR)/aws/operations" "$(ARTIFACTS_DIR)/aws/apns-training" "$(ARTIFACTS_DIR)/server/monitoring" "$(ARTIFACTS_DIR)/nigeyo-ios/Nigeyo/Resources"
	cp aws/live-registration.mjs aws/installation-store.mjs aws/self-registration.mjs aws/package*.json "$(ARTIFACTS_DIR)/aws/"
	cp aws/operations/handler.mjs aws/operations/index.html aws/operations/dashboard.js aws/operations/dashboard.css "$(ARTIFACTS_DIR)/aws/operations/"
	cp aws/apns-training/transport.mjs aws/apns-training/activity-push.mjs aws/apns-training/index.mjs aws/apns-training/package*.json "$(ARTIFACTS_DIR)/aws/apns-training/"
	cp server/monitoring/regions.mjs "$(ARTIFACTS_DIR)/server/monitoring/"
	cp nigeyo-ios/Nigeyo/Resources/notification-regions-aichi.json nigeyo-ios/Nigeyo/Resources/notification-regions-national.json "$(ARTIFACTS_DIR)/nigeyo-ios/Nigeyo/Resources/"
	cd "$(ARTIFACTS_DIR)/aws" && npm ci --omit=dev --ignore-scripts
	cd "$(ARTIFACTS_DIR)/aws/apns-training" && npm ci --omit=dev --ignore-scripts
