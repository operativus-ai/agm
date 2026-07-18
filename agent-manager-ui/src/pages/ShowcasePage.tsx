import { useState } from 'react';
import { Card } from '../shared/components/ui/Card';
import { Button } from '../shared/components/ui/Button';
import { Badge } from '../shared/components/ui/Badge';
import { Alert } from '../shared/components/ui/Alert';
import { Input } from '../shared/components/ui/Input';
import { Select } from '../shared/components/ui/Select';
import { Textarea } from '../shared/components/ui/Textarea';
import { Checkbox } from '../shared/components/ui/Checkbox';
import { Dialog } from '../shared/components/ui/Dialog';
import { Typography } from '../shared/components/ui/Typography';
import { Tabs } from '../shared/components/ui/Tabs';

export const ShowcasePage = () => {
  const [isDialogOpen, setIsDialogOpen] = useState(false);

  return (
    <div className="min-h-screen bg-base-200 pb-32">
      <div className="max-w-5xl mx-auto space-y-12">
        
        {/* Header */}
        <div className="space-y-4">
          <div className="flex justify-between items-center">
            <div>
              <Typography.Heading level={1} variant="h1">Component Showcase</Typography.Heading>
              <Typography.Text variant="lead">Agent Manager UI System</Typography.Text>
            </div>
            <div className="flex gap-2">
              <Badge variant="primary">v1.0</Badge>
              <Badge variant="secondary" outline>Beta</Badge>
            </div>
          </div>
          <Alert severity="info" title="System Ready" dismissible>
            All core components have been ported successfully.
          </Alert>
        </div>

        {/* Layout & Typography */}
        <section className="space-y-6">
          <Typography.Heading level={2}>Typography & Layout</Typography.Heading>
          <Card>
            <Card.Body className="space-y-4">
              <div className="space-y-2">
                <Typography.Heading level={1}>Heading 1</Typography.Heading>
                <Typography.Heading level={2}>Heading 2</Typography.Heading>
                <Typography.Heading level={3}>Heading 3</Typography.Heading>
              </div>
              <div className="space-y-2">
                <Typography.Text>Body text - The quick brown fox jumps over the lazy dog.</Typography.Text>
                <Typography.Text variant="small">Small text - Metadata and captions.</Typography.Text>
                <Typography.Text variant="muted">Muted text - Disabled or secondary info.</Typography.Text>
              </div>
            </Card.Body>
          </Card>
        </section>

        {/* Forms */}
        <section className="space-y-6">
          <Typography.Heading level={2}>Form Components</Typography.Heading>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
            <Card>
              <Card.Header><h3 className="font-semibold">Text Inputs</h3></Card.Header>
              <Card.Body className="space-y-4">
                <Input label="Email Address" placeholder="alex@example.com" required />
                <Input label="Username" placeholder="Enter username" error="Username is already taken" />
                <Input label="Search" placeholder="Search..." loading />
                <Textarea label="Bio" placeholder="Tell us about yourself" minRows={2} autoResize />
              </Card.Body>
            </Card>

            <Card>
              <Card.Header><h3 className="font-semibold">Selection Controls</h3></Card.Header>
              <Card.Body className="space-y-4">
                <Select 
                  label="Role" 
                  options={[
                    { label: 'Admin', value: 'admin' },
                    { label: 'User', value: 'user' },
                    { label: 'Guest', value: 'guest' },
                  ]} 
                />
                <div className="flex flex-col gap-2">
                  <span className="label-text font-medium">Notifications</span>
                  <Checkbox label="Email Notifications" defaultChecked />
                  <Checkbox label="SMS Alerts" />
                </div>
              </Card.Body>
            </Card>
          </div>
        </section>

        {/* Interactive */}
        <section className="space-y-6">
          <Typography.Heading level={2}>Interactive Elements</Typography.Heading>
          <Card>
            <Card.Body>
              <Tabs defaultValue="buttons">
                <Tabs.List className="mb-4">
                  <Tabs.Trigger value="buttons">Buttons</Tabs.Trigger>
                  <Tabs.Trigger value="alerts">Alerts</Tabs.Trigger>
                  <Tabs.Trigger value="dialog">Dialog</Tabs.Trigger>
                </Tabs.List>
                
                <Tabs.Content value="buttons" className="space-y-4">
                  <div className="flex flex-wrap gap-4">
                    <Button variant="primary">Primary</Button>
                    <Button variant="secondary">Secondary</Button>
                    <Button variant="outline">Outline</Button>
                    <Button variant="ghost">Ghost</Button>
                    <Button variant="danger">Danger</Button>
                  </div>
                  <div className="flex flex-wrap gap-4 items-center">
                    <Button size="lg">Large</Button>
                    <Button size="md">Medium</Button>
                    <Button size="sm">Small</Button>
                    <Button size="xs">Tiny</Button>
                  </div>
                  <div className="flex flex-wrap gap-4">
                    <Button loading>Loading</Button>
                    <Button disabled>Disabled</Button>
                  </div>
                </Tabs.Content>

                <Tabs.Content value="alerts" className="space-y-4">
                  <Alert severity="info" title="Information">This is a system update message.</Alert>
                  <Alert severity="success" title="Success">Operation completed successfully.</Alert>
                  <Alert severity="warning" title="Warning">Please check your configuration.</Alert>
                  <Alert severity="error" title="Error">Something went wrong!</Alert>
                </Tabs.Content>

                <Tabs.Content value="dialog" className="py-8 text-center">
                  <Button onClick={() => setIsDialogOpen(true)} variant="primary" size="lg">
                    Open Confirmation Dialog
                  </Button>
                </Tabs.Content>
              </Tabs>
            </Card.Body>
          </Card>
        </section>

      </div>

      <Dialog
        isOpen={isDialogOpen}
        setIsOpen={setIsDialogOpen}
        title="Confirm Action"
        content="Are you sure you want to proceed? This action cannot be undone."
        severity="warning"
        confirmLabel="Proceed"
        onConfirm={() => alert('Confirmed!')}
      />
    </div>
  );
}
