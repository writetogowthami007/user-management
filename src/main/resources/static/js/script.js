document.addEventListener('DOMContentLoaded', function() {
    const userForm = document.getElementById('userForm');
    const checkUserBtn = document.getElementById('checkUserBtn');
    const submitBtn = userForm.querySelector('button[type="submit"]');
    const responseMessage = document.getElementById('responseMessage');

    async function parseJsonIfPresent(response) {
        const contentType = response.headers.get('content-type') || '';
        if (contentType.includes('application/json')) {
            return response.json();
        }
        return {};
    }

    // Form submission handler
    userForm.addEventListener('submit', async function(e) {
        e.preventDefault();
        setLoadingState(true);
        
        const formData = {
            firstName: document.getElementById('firstName').value.trim(),
            lastName: document.getElementById('lastName').value.trim(),
            email: document.getElementById('email').value.trim().toLowerCase(),
            password: document.getElementById('password').value
        };

        try {
            const response = await fetch('/api/users', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify(formData)
            });

            const data = await parseJsonIfPresent(response);
            
            if (response.ok) {
                showMessage(data.message || 'User created successfully!', 'success');
                userForm.reset();
            } else if (response.status === 409) {
                showMessage('Email already exists. Use a different email address.', 'error');
            } else {
                showMessage(data.message || 'Error creating user', 'error');
            }
        } catch (error) {
            showMessage('An error occurred while creating the user', 'error');
            console.error('Error:', error);
        } finally {
            setLoadingState(false);
        }
    });

    // Credential validation handler
    checkUserBtn.addEventListener('click', async function() {
        setLoadingState(true);
        const email = document.getElementById('email').value.trim().toLowerCase();
        const password = document.getElementById('password').value;
        
        if (!email || !password) {
            showMessage('Please enter both email and password', 'error');
            setLoadingState(false);
            return;
        }

        try {
            const response = await fetch('/api/users/check', {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json'
                },
                body: JSON.stringify({ email, password })
            });
            const data = await parseJsonIfPresent(response);
            
            if (response.ok && data.status === 'valid') {
                showMessage('Credentials are valid', 'success');
            } else {
                showMessage('Invalid credentials', 'error');
            }
        } catch (error) {
            showMessage('An error occurred while validating credentials', 'error');
            console.error('Error:', error);
        } finally {
            setLoadingState(false);
        }
    });

    // Helper function to show messages
    function showMessage(message, type) {
        responseMessage.textContent = message;
        responseMessage.className = `response-message ${type}`;
        responseMessage.style.display = 'block';
        
        // Hide message after 5 seconds
        setTimeout(() => {
            responseMessage.style.display = 'none';
        }, 5000);
    }

    function setLoadingState(isLoading) {
        submitBtn.disabled = isLoading;
        checkUserBtn.disabled = isLoading;
    }
}); 