package com.example.tinyreminder;

import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;

import com.example.tinyreminder.fragments.FamilyFragment;
import com.example.tinyreminder.fragments.LoginFragment;
import com.example.tinyreminder.fragments.MapFragment;
import com.example.tinyreminder.fragments.ProfileFragment;
import com.example.tinyreminder.models.ParkingEvent;
import com.example.tinyreminder.services.LocationUpdateService;
import com.example.tinyreminder.services.ParkingDetectionService;
import com.example.tinyreminder.utils.DatabaseManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessaging;

import android.Manifest;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_NOTIFICATION_PERMISSION = 2;
    private static final int LOCATION_PERMISSION_REQUEST_CODE = 1;
    private static final int BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE = 3;
    private FirebaseAuth mAuth;
    private FirebaseAuth.AuthStateListener mAuthListener;
    private BottomNavigationView bottomNavigationView;
    private DatabaseManager dbManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mAuth = FirebaseAuth.getInstance();
        dbManager = new DatabaseManager();

        createNotificationChannel();
        setupFirebaseMessaging();
        checkNotificationPermission();
        checkLocationPermission();

        setupBottomNavigation();
        setupBackPressedCallback();
        setupAuthStateListener();
        checkUserAuthState();

        handleNotificationIntent(getIntent());
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    "ParkingReminders",
                    "Parking Reminders",
                    NotificationManager.IMPORTANCE_HIGH);
            NotificationManager notificationManager = getSystemService(NotificationManager.class);
            notificationManager.createNotificationChannel(channel);
        }
    }

    private void setupFirebaseMessaging() {
        FirebaseMessaging.getInstance().getToken()
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful()) {
                        Log.w(TAG, "Fetching FCM registration token failed", task.getException());
                        return;
                    }
                    String token = task.getResult();
                    saveTokenToDatabase(token);
                    Log.d(TAG, "FCM Token: " + token);
                });
    }

    private void saveTokenToDatabase(String token) {
        FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
        if (currentUser != null) {
            String userId = currentUser.getUid();
            dbManager.saveFcmToken(userId, token);
        }
    }

    private void checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQUEST_NOTIFICATION_PERMISSION);
            }
        }
    }

    private void checkLocationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        LOCATION_PERMISSION_REQUEST_CODE);
            } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_BACKGROUND_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                showBackgroundLocationPermissionRationale();
            } else {
                startLocationServices();
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION},
                        LOCATION_PERMISSION_REQUEST_CODE);
            } else {
                startLocationServices();
            }
        }
    }

    private void showBackgroundLocationPermissionRationale() {
        new AlertDialog.Builder(this)
                .setTitle("Background Location Access")
                .setMessage("This app needs background location access to track your location even when the app is not in use.")
                .setPositiveButton("Allow", (dialog, which) -> {
                    ActivityCompat.requestPermissions(MainActivity.this,
                            new String[]{Manifest.permission.ACCESS_BACKGROUND_LOCATION},
                            BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE);
                })
                .setNegativeButton("Deny", (dialog, which) -> {
                    Toast.makeText(MainActivity.this, "Background location permission is required for continuous tracking", Toast.LENGTH_LONG).show();
                })
                .create()
                .show();
    }

    private void setupBottomNavigation() {
        bottomNavigationView = findViewById(R.id.bottom_navigation);
        bottomNavigationView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int itemId = item.getItemId();

            if (itemId == R.id.navigation_profile) {
                selectedFragment = new ProfileFragment();
            } else if (itemId == R.id.navigation_family) {
                selectedFragment = new FamilyFragment();
            } else if (itemId == R.id.navigation_map) {
                FirebaseUser currentUser = FirebaseAuth.getInstance().getCurrentUser();
                if (currentUser != null) {
                    selectedFragment = MapFragment.newInstance(true, currentUser.getUid());
                }
            }

            if (selectedFragment != null) {
                loadFragment(selectedFragment);
            }
            return true;
        });
    }

    private void setupBackPressedCallback() {
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getSupportFragmentManager().getBackStackEntryCount() > 0) {
                    getSupportFragmentManager().popBackStack();
                } else {
                    finish();
                }
            }
        });
    }

    private void setupAuthStateListener() {
        mAuthListener = firebaseAuth -> {
            FirebaseUser user = firebaseAuth.getCurrentUser();
            Log.d(TAG, "onAuthStateChanged: " + (user != null ? user.getUid() : "null"));
            if (user != null) {
                navigateToProfile();
            } else {
                navigateToLogin();
            }
        };
    }

    private void checkUserAuthState() {
        FirebaseUser currentUser = mAuth.getCurrentUser();
        if (currentUser != null) {
            Log.d(TAG, "User is signed in, navigating to profile");
            navigateToProfile();
        } else {
            Log.d(TAG, "User is not signed in, navigating to login");
            navigateToLogin();
        }
    }

    public void loadFragment(Fragment fragment) {
        FragmentManager fragmentManager = getSupportFragmentManager();
        FragmentTransaction fragmentTransaction = fragmentManager.beginTransaction();
        fragmentTransaction.replace(R.id.fragment_container, fragment);
        fragmentTransaction.addToBackStack(null);
        fragmentTransaction.commit();
    }

    public void navigateToLogin() {
        Log.d(TAG, "Navigating to login");
        loadFragment(new LoginFragment());
        bottomNavigationView.setVisibility(View.GONE);
    }

    public void navigateToProfile() {
        Log.d(TAG, "MainActivity: navigateToProfile called");
        Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.fragment_container);

        if (currentFragment instanceof ProfileFragment) {
            Log.d(TAG, "MainActivity: ProfileFragment is already displayed");
            return;
        }

        Fragment profileFragment = new ProfileFragment();
        loadFragment(profileFragment);
        Log.d(TAG, "MainActivity: after loadFragment call");

        bottomNavigationView.setVisibility(View.VISIBLE);
        bottomNavigationView.setSelectedItemId(R.id.navigation_profile);
    }

    @Override
    protected void onStart() {
        super.onStart();
        Log.d(TAG, "MainActivity: onStart");
        mAuth.addAuthStateListener(mAuthListener);
        checkUserAuthState();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (mAuthListener != null) {
            mAuth.removeAuthStateListener(mAuthListener);
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleNotificationIntent(intent);
    }

    private void handleNotificationIntent(Intent intent) {
        if (intent.hasExtra("eventId")) {
            String eventId = intent.getStringExtra("eventId");
            showParkingEventDialog(eventId);
        }
    }

    private void showParkingEventDialog(String eventId) {
        dbManager.getParkingEvent(eventId, new ValueEventListener() {
            @Override
            public void onDataChange(@NonNull DataSnapshot dataSnapshot) {
                ParkingEvent event = dataSnapshot.getValue(ParkingEvent.class);
                if (event != null) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(MainActivity.this);
                    builder.setTitle("Parking Reminder");
                    builder.setMessage("Is the child still in the car?");
                    builder.setPositiveButton("Yes", (dialog, which) -> {
                        dbManager.updateParkingEventStatus(eventId, "CHILD_PRESENT", task -> {
                            if (task.isSuccessful()) {
                                Toast.makeText(MainActivity.this, "Thank you for confirming. Please attend to the child.", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    builder.setNegativeButton("No", (dialog, which) -> {
                        dbManager.updateParkingEventStatus(eventId, "CHILD_NOT_PRESENT", task -> {
                            if (task.isSuccessful()) {
                                Toast.makeText(MainActivity.this, "Thank you for confirming. The child is safe.", Toast.LENGTH_SHORT).show();
                            }
                        });
                    });
                    builder.show();
                }
            }

            @Override
            public void onCancelled(@NonNull DatabaseError databaseError) {
                Log.e(TAG, "Error fetching parking event", databaseError.toException());
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == LOCATION_PERMISSION_REQUEST_CODE) {
            boolean allPermissionsGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allPermissionsGranted = false;
                    break;
                }
            }

            if (allPermissionsGranted) {
                checkLocationPermission();
            } else {
                Toast.makeText(this, "All permissions are necessary for the app to function properly", Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == BACKGROUND_LOCATION_PERMISSION_REQUEST_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationServices();
            } else {
                Toast.makeText(this, "Background location permission is required for continuous tracking", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startLocationServices() {
        Intent locationServiceIntent = new Intent(this, LocationUpdateService.class);
        Intent parkingDetectionServiceIntent = new Intent(this, ParkingDetectionService.class);

        startForegroundService(locationServiceIntent);
        startForegroundService(parkingDetectionServiceIntent);
    }
}